package io.github.benhendayoussef.idempotency.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.benhendayoussef.idempotency.api.ConflictPolicy;
import io.github.benhendayoussef.idempotency.api.FingerprintMismatchException;
import io.github.benhendayoussef.idempotency.api.Idempotent;
import io.github.benhendayoussef.idempotency.api.IdempotencyConflictException;
import io.github.benhendayoussef.idempotency.api.IdempotencyKeyRequiredException;
import io.github.benhendayoussef.idempotency.api.IdempotencyMetrics;
import io.github.benhendayoussef.idempotency.api.IdempotencyPrincipalRequiredException;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord.State;
import io.github.benhendayoussef.idempotency.api.IdempotencyScope;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore.ClaimResult;
import io.github.benhendayoussef.idempotency.api.IdempotencyStoreUnavailableException;
import io.github.benhendayoussef.idempotency.api.ScopeResolver;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties.OnStoreFailure;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties.ReleaseOn;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeoutException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The core of the library: claims the idempotency key, executes the handler once, and replays
 * the stored result for every duplicate. See the module Javadoc / README for the state machine.
 */
@Aspect
public class IdempotencyAspect implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);
    private static final String REPLAY_HEADER = "Idempotent-Replay";
    private static final String ERROR_PAYLOAD_TYPE = "__error__";
    private static final Duration MAX_BACKOFF = Duration.ofMillis(400);
    private static final Pattern SIMPLE_DURATION = Pattern.compile("(?i)^(\\d+)\\s*(ms|s|m|h|d)$");

    /** Sentinel: the previous claim was released while we looked at it; re-enter the claim loop. */
    private static final Object RETRY_CLAIM = new Object();

    private final IdempotencyStore store;
    private final IdempotencyProperties props;
    private final ArgumentFingerprinter fingerprinter;
    private final IdempotencyKeyComposer composer;
    private final Map<IdempotencyScope, ScopeResolver> scopes;
    private final ObjectMapper payloadMapper;
    private final IdempotencyMetrics metrics;
    private final ExpressionParser spelParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNames = new DefaultParameterNameDiscoverer();

    public IdempotencyAspect(IdempotencyStore store, IdempotencyProperties props,
            ArgumentFingerprinter fingerprinter, IdempotencyKeyComposer composer,
            Map<IdempotencyScope, ScopeResolver> scopes, ObjectMapper payloadMapper,
            IdempotencyMetrics metrics) {
        this.store = store;
        this.props = props;
        this.fingerprinter = fingerprinter;
        this.composer = composer;
        this.scopes = scopes;
        this.payloadMapper = payloadMapper;
        this.metrics = metrics;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {

        HttpServletRequest request = currentRequest();
        if (request == null) {
            return pjp.proceed();          // not in a web context
        }

        String clientKey = resolveClientKey(idempotent, pjp, request);
        if (clientKey == null) {
            if (props.isRequireKey()) {
                throw new IdempotencyKeyRequiredException();
            }
            metrics.missingKey();
            return pjp.proceed();
        }

        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Duration ttl = resolveTtl(idempotent);
        String namespace;
        try {
            namespace = resolveNamespace(idempotent);
        } catch (MissingPrincipalException e) {
            switch (props.getOnMissingPrincipal()) {
                case SKIP -> {
                    log.debug("No authenticated principal for scoped idempotency key; skipping idempotency for this request", e);
                    metrics.principalMissing();
                    return pjp.proceed();
                }
                case REJECT -> throw new IdempotencyPrincipalRequiredException(e);
                default -> {
                    log.debug("No authenticated principal for scoped idempotency key; falling back to the global namespace", e);
                    namespace = "";
                }
            }
        }
        String storeKey = composer.compose(clientKey, namespace, request);
        String fp = idempotent.fingerprintArgs()
                ? fingerprinter.fingerprint(method, pjp.getArgs()) : "";

        // Bounded to 2 attempts: a released-while-we-waited record re-enters the claim path once.
        // Never recurse here - that is an infinite loop waiting to happen under sustained churn.
        for (int attempt = 0; attempt < 2; attempt++) {
            ClaimResult claim;
            try {
                claim = store.claim(storeKey, fp, ttl);
            } catch (RuntimeException e) {
                return handleStoreFailure(pjp, e);
            }

            if (claim instanceof ClaimResult.AlreadyHeld held) {
                Object outcome = handleExisting(held.record(), storeKey, fp, method, idempotent);
                if (outcome == RETRY_CLAIM) {
                    continue;
                }
                return outcome;
            }

            return execute(pjp, storeKey, fp, ttl, method);
        }
        return execute(pjp, storeKey, fp, ttl, method);
    }

    private Object execute(ProceedingJoinPoint pjp, String storeKey, String fp, Duration ttl, Method method)
            throws Throwable {
        try {
            Object result = pjp.proceed();
            IdempotencyRecord record = toRecord(result, fp, method);
            if (exceedsMaxPayload(record)) {
                store.release(storeKey);
                log.warn("Idempotent response for key {} exceeds idempotency.max-payload-size; not cached", storeKey);
            } else {
                store.complete(storeKey, record, ttl);
            }
            metrics.executed();
            return result;
        } catch (Throwable t) {
            if (shouldRelease(t)) {
                store.release(storeKey);
                metrics.released();
            } else {
                store.complete(storeKey, toErrorRecord(t, fp), ttl);
            }
            throw t;
        }
    }

    private Object handleStoreFailure(ProceedingJoinPoint pjp, RuntimeException cause) throws Throwable {
        metrics.storeFailure();
        if (props.getOnStoreFailure() == OnStoreFailure.FAIL) {
            throw new IdempotencyStoreUnavailableException(cause);
        }
        log.warn("IdempotencyStore unavailable; proceeding without idempotency protection", cause);
        return pjp.proceed();
    }

    /** Returns the replay value, throws, or {@link #RETRY_CLAIM} to re-enter the claim loop. */
    private Object handleExisting(IdempotencyRecord record, String key, String fp, Method method, Idempotent ann)
            throws Throwable {

        if (!fp.isEmpty() && !record.fingerprint().isEmpty() && !fp.equals(record.fingerprint())) {
            metrics.fingerprintMismatch();
            throw new FingerprintMismatchException();
        }

        if (record.state() == State.COMPLETED) {
            if (!isCompatible(record, method)) {
                // Signature changed since the record was written: treat it as stale.
                store.release(key);
                return RETRY_CLAIM;
            }
            metrics.replayed();
            markReplayHeader();
            return deserialize(record, method);
        }

        // IN_PROGRESS
        ConflictPolicy policy = effectivePolicy(ann);
        if (policy == ConflictPolicy.FAIL_FAST) {
            metrics.conflict();
            throw new IdempotencyConflictException();
        }

        Instant deadline = Instant.now().plus(props.getWaitTimeout());
        Duration backoff = Duration.ofMillis(25);
        while (Instant.now().isBefore(deadline)) {
            Thread.sleep(backoff.toMillis());
            backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);

            var refreshed = store.find(key);
            if (refreshed.isEmpty()) {
                // Original failed and released - the caller's bounded loop re-enters claim().
                return RETRY_CLAIM;
            }
            if (refreshed.get().state() == State.COMPLETED) {
                if (!isCompatible(refreshed.get(), method)) {
                    store.release(key);
                    return RETRY_CLAIM;
                }
                metrics.replayedAfterWait();
                markReplayHeader();
                return deserialize(refreshed.get(), method);
            }
        }
        metrics.waitTimeout();
        throw new IdempotencyConflictException();
    }

    private boolean isCompatible(IdempotencyRecord record, Method method) {
        return ERROR_PAYLOAD_TYPE.equals(record.payloadType()) || typeNameOf(method).equals(record.payloadType());
    }

    private ConflictPolicy effectivePolicy(Idempotent ann) {
        return ann.onConflict() == ConflictPolicy.DEFAULT ? props.getOnConflict() : ann.onConflict();
    }

    private boolean exceedsMaxPayload(IdempotencyRecord record) {
        if (record.payload() == null) {
            return false;
        }
        return record.payload().length() > props.getMaxPayloadSize().toBytes();
    }

    // --- Key resolution -----------------------------------------------------------------

    private String resolveClientKey(Idempotent ann, ProceedingJoinPoint pjp, HttpServletRequest request) {
        if (!ann.key().isBlank()) {
            Object value = evaluateSpel(ann.key(), pjp);
            return value == null ? null : value.toString();
        }
        String header = ann.keyHeader().isBlank() ? props.getHeaderName() : ann.keyHeader();
        String value = request.getHeader(header);
        return (value == null || value.isBlank()) ? null : value;
    }

    private Object evaluateSpel(String expression, ProceedingJoinPoint pjp) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String[] names = paramNames.getParameterNames(signature.getMethod());
        StandardEvaluationContext context = new StandardEvaluationContext();
        Object[] args = pjp.getArgs();
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                context.setVariable(names[i], args[i]);
            }
        }
        return spelParser.parseExpression(expression).getValue(context);
    }

    private Duration resolveTtl(Idempotent ann) {
        return ann.ttl().isBlank() ? props.getDefaultTtl() : parseDuration(ann.ttl());
    }

    static Duration parseDuration(String value) {
        String trimmed = value.trim();
        Matcher m = SIMPLE_DURATION.matcher(trimmed);
        if (m.matches()) {
            long amount = Long.parseLong(m.group(1));
            return switch (m.group(2).toLowerCase()) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalArgumentException("Unsupported TTL unit in '" + value + "'");
            };
        }
        return Duration.parse(trimmed); // ISO-8601, e.g. PT30M
    }

    private String resolveNamespace(Idempotent ann) {
        IdempotencyScope scope = ann.scope() == IdempotencyScope.DEFAULT ? props.getScope() : ann.scope();
        if (scope == IdempotencyScope.GLOBAL) {
            return "";
        }
        ScopeResolver resolver = scopes.get(scope);
        if (resolver == null) {
            throw new IllegalStateException(
                    "No ScopeResolver registered for scope " + scope + " - register a bean implementing ScopeResolver");
        }
        try {
            return resolver.namespace();
        } catch (IllegalStateException e) {
            // The resolver itself is registered (a wiring/config problem, handled above) but this
            // specific request has no resolvable principal - a per-request condition, not a
            // startup-time one. Translated to a distinct type so around() doesn't also swallow the
            // "no resolver registered at all" case above under idempotency.on-missing-principal.
            throw new MissingPrincipalException(e);
        }
    }

    /** Internal signal: {@link #resolveNamespace} found a registered resolver but no principal for this request. */
    private static final class MissingPrincipalException extends RuntimeException {
        MissingPrincipalException(Throwable cause) {
            super(cause);
        }
    }

    // --- Failure policy -------------------------------------------------------------------

    private boolean shouldRelease(Throwable t) {
        if (t instanceof TimeoutException) {
            return props.getReleaseOn().contains(ReleaseOn.TIMEOUT);
        }
        return resolveStatus(t) >= 500 && props.getReleaseOn().contains(ReleaseOn.FIVE_XX);
    }

    private int resolveStatus(Throwable t) {
        if (t instanceof ErrorResponse er) {
            return er.getStatusCode().value();
        }
        org.springframework.web.bind.annotation.ResponseStatus ann =
                org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                        t.getClass(), org.springframework.web.bind.annotation.ResponseStatus.class);
        if (ann != null) {
            return ann.code().value();
        }
        // Unmapped exceptions become 500 under Spring's default handling: treat the same way.
        return 500;
    }

    // --- Serialization ----------------------------------------------------------------------

    private IdempotencyRecord toRecord(Object result, String fp, Method method) {
        int status = 200;
        Object body = result;
        if (result instanceof ResponseEntity<?> re) {
            status = re.getStatusCode().value();
            body = re.getBody();
        }
        String json = writeJson(body);
        return new IdempotencyRecord(State.COMPLETED, fp, status, typeNameOf(method), json, Instant.now());
    }

    private IdempotencyRecord toErrorRecord(Throwable t, String fp) {
        int status = resolveStatus(t);
        Object body = (t instanceof ErrorResponse er) ? er.getBody() : problemDetailFor(status, t.getMessage());
        return new IdempotencyRecord(State.COMPLETED, fp, status, ERROR_PAYLOAD_TYPE, writeJson(body), Instant.now());
    }

    private ProblemDetail problemDetailFor(int status, String message) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        if (message != null) {
            pd.setDetail(message);
        }
        return pd;
    }

    private String writeJson(Object body) {
        if (body == null) {
            return null;
        }
        try {
            return payloadMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize idempotent response body", e);
        }
    }

    private Object deserialize(IdempotencyRecord rec, Method method) {
        if (ERROR_PAYLOAD_TYPE.equals(rec.payloadType())) {
            // A method with a plain (non-ResponseEntity) return type can't carry an arbitrary
            // status through its declared type, so write the response directly and return null -
            // Spring MVC's message-converter path leaves an already-written response alone.
            if (!ResponseEntity.class.isAssignableFrom(method.getReturnType())) {
                writeRawErrorResponse(rec.status(), rec.payload());
                return null;
            }
            Object body = readJson(rec.payload(), ProblemDetail.class);
            return ResponseEntity.status(rec.status()).body(body);
        }

        Type generic = method.getGenericReturnType();
        boolean wrapped = ResponseEntity.class.isAssignableFrom(method.getReturnType());
        Type bodyType = wrapped && generic instanceof ParameterizedType pt ? pt.getActualTypeArguments()[0] : generic;

        Object body = rec.payload() == null ? null : readJson(rec.payload(), bodyType);
        return wrapped ? ResponseEntity.status(rec.status()).body(body) : body;
    }

    private Object readJson(String json, Type type) {
        if (json == null) {
            return null;
        }
        try {
            return payloadMapper.readValue(json, payloadMapper.getTypeFactory().constructType(type));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize idempotent response body", e);
        }
    }

    /** Note: resolves from the live method, never from {@code payloadType} - see the security note in the README. */
    private String typeNameOf(Method method) {
        Type generic = method.getGenericReturnType();
        boolean wrapped = ResponseEntity.class.isAssignableFrom(method.getReturnType());
        Type bodyType = wrapped && generic instanceof ParameterizedType pt ? pt.getActualTypeArguments()[0] : generic;
        return bodyType.getTypeName();
    }

    // --- Servlet plumbing --------------------------------------------------------------------

    private HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    private void markReplayHeader() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra && sra.getResponse() != null) {
            sra.getResponse().setHeader(REPLAY_HEADER, "true");
        }
    }

    private void writeRawErrorResponse(int status, String json) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra) || sra.getResponse() == null) {
            return;
        }
        HttpServletResponse response = sra.getResponse();
        response.setStatus(status);
        if (json != null) {
            response.setContentType("application/problem+json");
            try {
                response.getWriter().write(json);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Failed to write idempotent error replay", e);
            }
        }
    }

    @Override
    public int getOrder() {
        return props.getAspectOrder();
    }
}
