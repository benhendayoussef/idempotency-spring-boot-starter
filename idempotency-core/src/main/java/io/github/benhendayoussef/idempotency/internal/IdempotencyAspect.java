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
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties.OnSilentRollback;
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
    /**
     * Null unless transaction joining is enabled and a JDBC store is active. Null means the v0.1
     * separate-commits behaviour, which is the default.
     */
    private final TransactionRunner txRunner;
    private final ExpressionParser spelParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNames = new DefaultParameterNameDiscoverer();

    /**
     * @param txRunner {@code null} for the default separate-commits behaviour. Kept as a required
     *                 argument rather than an overload so every call site has to state which mode it
     *                 is building - a convenience constructor here would make "no transaction
     *                 joining" the silent case, and that is the case worth being explicit about.
     */
    public IdempotencyAspect(IdempotencyStore store, IdempotencyProperties props,
            ArgumentFingerprinter fingerprinter, IdempotencyKeyComposer composer,
            Map<IdempotencyScope, ScopeResolver> scopes, ObjectMapper payloadMapper,
            IdempotencyMetrics metrics, TransactionRunner txRunner) {
        this.store = store;
        this.props = props;
        this.fingerprinter = fingerprinter;
        this.composer = composer;
        this.scopes = scopes;
        this.payloadMapper = payloadMapper;
        this.metrics = metrics;
        this.txRunner = txRunner;
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
                // claimTtlFor, not ttl: the claim is a lease on an in-flight request, the retention
                // TTL is how long the finished response stays replayable. Passing one value for both
                // is what let a crashed process lock a key for 24 hours.
                claim = store.claim(storeKey, fp, props.claimTtlFor(ttl));
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
        // around() has already claimed and committed the key by the time we get here, outside any
        // transaction this method opens. That ordering is load-bearing: if the IN_PROGRESS row only
        // became visible when the handler's transaction commits, every concurrent duplicate would
        // race straight past the claim instead of seeing it.
        return txRunner == null
                ? executeSeparateCommits(pjp, storeKey, fp, ttl, method)
                : executeJoined(pjp, storeKey, fp, ttl, method);
    }

    /**
     * Default path: the handler commits its own transaction (if it has one), then the completion
     * write commits separately. At-least-once — a crash between the two leaves business data with
     * no completion record. Unchanged from v0.1.
     */
    private Object executeSeparateCommits(
            ProceedingJoinPoint pjp, String storeKey, String fp, Duration ttl, Method method) throws Throwable {
        try {
            Object result = pjp.proceed();
            completeOrRelease(storeKey, result, fp, ttl, method);
            metrics.executed();
            return result;
        } catch (Throwable t) {
            settleFailure(storeKey, fp, ttl, t);
            throw t;
        }
    }

    /**
     * Joined path ({@code idempotency.jdbc.join-transaction=true}): the handler and the completion
     * write share one transaction, so they commit or roll back together — exactly-once.
     *
     * <p>The handler's own {@code @Transactional(REQUIRED)} joins this transaction rather than
     * opening and closing its own beforehand, which is what makes the single commit possible.
     */
    private Object executeJoined(
            ProceedingJoinPoint pjp, String storeKey, String fp, Duration ttl, Method method) throws Throwable {
        // Captured inside the transaction so it survives an UnexpectedRollbackException raised at
        // commit time, after the handler has already produced its result.
        Object[] handlerResult = new Object[1];
        boolean[] oversized = new boolean[1];

        try {
            Object result = txRunner.inTransaction(() -> {
                Object r = pjp.proceed();
                handlerResult[0] = r;
                IdempotencyRecord record = toRecord(r, fp, method);
                if (exceedsMaxPayload(record)) {
                    // Deliberately not released here. store.release() is REQUIRES_NEW, so calling it
                    // now would suspend this transaction and demand a second connection while the
                    // first is still held - fine on one thread, a deadlock shape once enough
                    // concurrent requests saturate the pool. Nothing needs it to be atomic with the
                    // handler: skipping the completion write is what leaves the response uncached,
                    // and the claim can be cleaned up once this transaction is closed.
                    oversized[0] = true;
                } else {
                    store.complete(storeKey, record, ttl);
                }
                return r;
            });

            if (oversized[0]) {
                releaseQuietly(storeKey);
                log.warn("Idempotent response for key {} exceeds idempotency.max-payload-size; not cached", storeKey);
            }
            metrics.executed();
            return result;

        } catch (Throwable t) {
            if (isUnexpectedRollback(t)) {
                // The handler called setRollbackOnly() and returned normally, so it marked the
                // *shared* transaction rollback-only and we only learn about it here, at commit.
                // The completion write rolled back with it, which is the correct exactly-once
                // outcome. Release the claim so the key is immediately reclaimable rather than
                // stranded as IN_PROGRESS until its TTL expires.
                //
                // Either way the claim is released - nothing was committed, so the key must not stay
                // held. What differs is what the caller is told, which is idempotency.jdbc.
                // on-silent-rollback.
                releaseQuietly(storeKey);
                metrics.released();
                log.warn("Handler for idempotency key {} marked the transaction rollback-only; "
                        + "nothing was committed and the key has been released", storeKey);

                if (props.getJdbc().getOnSilentRollback() == OnSilentRollback.FAIL) {
                    // The response would describe data that does not exist. Surfacing the rollback
                    // is the point, so the original exception propagates rather than a synthetic
                    // one - it names the transaction that rolled back.
                    throw t;
                }
                // Default: the handler made two explicit choices - roll back, and report success -
                // and the library reports the one it returned. This is what 0.1 through 0.4 did.
                return handlerResult[0];
            }

            settleFailure(storeKey, fp, ttl, t);
            throw t;
        }
    }

    /**
     * Store the response, unless it is too large to be worth caching. Default path only - the joined
     * path inlines the same decision so it can defer the release until its transaction has closed.
     */
    private void completeOrRelease(String storeKey, Object result, String fp, Duration ttl, Method method) {
        IdempotencyRecord record = toRecord(result, fp, method);
        if (exceedsMaxPayload(record)) {
            store.release(storeKey);
            log.warn("Idempotent response for key {} exceeds idempotency.max-payload-size; not cached", storeKey);
        } else {
            store.complete(storeKey, record, ttl);
        }
    }

    /**
     * Applies the failure policy after the handler threw.
     *
     * <p>In joined mode the wrapping transaction has already rolled back and been unbound from the
     * thread by the time this runs, so {@code store.complete()}'s {@code REQUIRED} propagation
     * starts a fresh transaction — which is exactly what the 4xx-keeps policy needs. A terminal 4xx
     * is a deterministic client error and stays replayable even though the business data it
     * described was discarded, matching v0.1 in both modes.
     */
    private void settleFailure(String storeKey, String fp, Duration ttl, Throwable t) {
        if (shouldRelease(t)) {
            store.release(storeKey);
            metrics.released();
        } else {
            store.complete(storeKey, toErrorRecord(t, fp), ttl);
        }
    }

    /** Releasing is best-effort cleanup; failing here must not mask the outcome being reported. */
    private void releaseQuietly(String storeKey) {
        try {
            store.release(storeKey);
        } catch (RuntimeException e) {
            log.warn("Failed to release idempotency key {} after a rolled-back transaction; "
                    + "it will be reclaimed when its TTL expires", storeKey, e);
        }
    }

    /**
     * Detects Spring's {@code UnexpectedRollbackException} by name.
     *
     * <p>By name because {@code idempotency-core} has no {@code spring-tx} dependency — the aspect
     * has to work when the Redis or in-memory store is active and no transaction manager exists.
     * The cause chain is walked too, since a transaction manager may wrap it.
     */
    private static boolean isUnexpectedRollback(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if ("org.springframework.transaction.UnexpectedRollbackException".equals(c.getClass().getName())) {
                return true;
            }
        }
        return false;
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
