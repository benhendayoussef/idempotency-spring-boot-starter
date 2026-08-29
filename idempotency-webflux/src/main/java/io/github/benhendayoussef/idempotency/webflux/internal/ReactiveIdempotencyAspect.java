package io.github.benhendayoussef.idempotency.webflux.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.benhendayoussef.idempotency.api.ConflictPolicy;
import io.github.benhendayoussef.idempotency.api.FingerprintMismatchException;
import io.github.benhendayoussef.idempotency.api.IdempotencyConflictException;
import io.github.benhendayoussef.idempotency.api.IdempotencyKeyRequiredException;
import io.github.benhendayoussef.idempotency.api.IdempotencyMetrics;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord.State;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore.ClaimResult;
import io.github.benhendayoussef.idempotency.api.IdempotencyStoreUnavailableException;
import io.github.benhendayoussef.idempotency.api.Idempotent;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties;
import io.github.benhendayoussef.idempotency.internal.ArgumentFingerprinter;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The WebFlux counterpart to {@code IdempotencyAspect}, for handlers that return {@code Mono}.
 *
 * <p>It is a separate aspect rather than a branch inside the servlet one because almost nothing
 * carries over. The servlet aspect reads the request from a ThreadLocal, calls the store inline, and
 * decides the outcome before returning; here the request lives in the Reactor context, every store
 * call has to leave the event loop, and the outcome is only known when the returned {@code Mono}
 * completes - so the whole claim/execute/complete sequence is assembled as an operator chain instead
 * of executed as statements.
 *
 * <h2>Two limitations worth stating plainly</h2>
 *
 * <p><strong>The store is blocking, so store calls run on {@code boundedElastic}.</strong> Every
 * {@code IdempotencyStore} implementation is synchronous JDBC or Lettuce-blocking, and calling one
 * on an event-loop thread is the classic way to stall a reactive application. Scheduling them onto
 * the elastic pool is correct but it is not free: an idempotent endpoint costs two thread handoffs
 * that a plain one does not. A genuinely reactive store SPI (R2DBC, reactive Redis) would remove
 * that, and is deliberately not in this change.
 *
 * <p><strong>Only {@code Mono} is advised.</strong> A {@code Flux} return is a stream, and this
 * library captures a single response value to replay later - the same reason the servlet side does
 * not support streaming responses. A {@code Flux}-returning handler passes through untouched rather
 * than being half-supported.
 */
@Aspect
public class ReactiveIdempotencyAspect implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(ReactiveIdempotencyAspect.class);

    /** Marks a stored record as an error response rather than a handler return value. */
    private static final String ERROR_PAYLOAD_TYPE = "!error";

    private final IdempotencyStore store;
    private final IdempotencyProperties props;
    private final ArgumentFingerprinter fingerprinter;
    private final ObjectMapper payloadMapper;
    private final IdempotencyMetrics metrics;

    public ReactiveIdempotencyAspect(IdempotencyStore store, IdempotencyProperties props,
            ArgumentFingerprinter fingerprinter, ObjectMapper payloadMapper, IdempotencyMetrics metrics) {
        this.store = store;
        this.props = props;
        this.fingerprinter = fingerprinter;
        this.payloadMapper = payloadMapper;
        this.metrics = metrics;
    }

    @Override
    public int getOrder() {
        return props.getAspectOrder();
    }

    @Around("@annotation(io.github.benhendayoussef.idempotency.api.Idempotent)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();

        if (!Mono.class.isAssignableFrom(method.getReturnType())) {
            // Flux, or a plain blocking return in a reactive application. Passing it through is the
            // honest outcome: advising it would either block the event loop or capture only the
            // first element of a stream, and both are worse than doing nothing.
            log.warn("@Idempotent on {}.{} returns {} - only Mono is supported on WebFlux, so the "
                            + "annotation has no effect here",
                    method.getDeclaringClass().getSimpleName(), method.getName(),
                    method.getReturnType().getSimpleName());
            return pjp.proceed();
        }

        Idempotent ann = method.getAnnotation(Idempotent.class);

        // deferContextual, not defer: the exchange is only reachable once someone subscribes, and
        // nothing before that point knows which request this is.
        return Mono.deferContextual(ctx -> {
            ServerWebExchange exchange = ctx.hasKey(IdempotencyExchangeContextFilter.CONTEXT_KEY)
                    ? ctx.get(IdempotencyExchangeContextFilter.CONTEXT_KEY)
                    : null;
            if (exchange == null) {
                // The filter is auto-registered, so this means someone replaced the filter chain.
                // Failing loudly beats silently executing every duplicate.
                return Mono.error(new IllegalStateException(
                        "No ServerWebExchange in the Reactor context. IdempotencyExchangeContextFilter "
                        + "must be registered as a WebFilter for @Idempotent to work on WebFlux."));
            }
            return applyIdempotency(pjp, method, ann, exchange);
        });
    }

    private Mono<Object> applyIdempotency(ProceedingJoinPoint pjp, Method method, Idempotent ann,
            ServerWebExchange exchange) {
        String headerName = ann.keyHeader().isBlank() ? props.getHeaderName() : ann.keyHeader();
        String clientKey = exchange.getRequest().getHeaders().getFirst(headerName);

        if (clientKey == null || clientKey.isBlank()) {
            metrics.missingKey();
            if (props.isRequireKey()) {
                return Mono.error(new IdempotencyKeyRequiredException());
            }
            return proceedAsMono(pjp);
        }

        String storeKey = ReactiveKeyComposer.compose(clientKey, "", exchange);
        String fingerprint = ann.fingerprintArgs() ? fingerprinter.fingerprint(method, pjp.getArgs()) : "";
        Duration ttl = ann.ttl().isBlank() ? props.getDefaultTtl() : Duration.parse("PT" + ann.ttl());

        // The claim result is carried in an Optional rather than signalled by an empty Mono. An
        // earlier version used switchIfEmpty to mean "the store failed, proceed unprotected", which
        // was wrong in a way only a handler returning Mono.empty() reveals: a legitimately empty
        // response also completes empty, so the handler ran a second time - and a replay of that
        // stored empty response ran it a third. Emptiness is a valid outcome here, so it cannot
        // double as a control signal.
        return blocking(() -> store.claim(storeKey, fingerprint, ttl))
                .map(java.util.Optional::of)
                .onErrorResume(RuntimeException.class, this::onClaimFailure)
                .flatMap(claim -> claim
                        .map(c -> c instanceof ClaimResult.AlreadyHeld held
                                ? handleExisting(pjp, method, held.record(), storeKey, fingerprint, ttl)
                                : execute(pjp, method, storeKey, fingerprint, ttl))
                        // Empty Optional: the store was unreachable and the policy said PROCEED.
                        .orElseGet(() -> proceedAsMono(pjp)));
    }

    private Mono<java.util.Optional<ClaimResult>> onClaimFailure(RuntimeException e) {
        metrics.storeFailure();
        if (props.getOnStoreFailure() == IdempotencyProperties.OnStoreFailure.FAIL) {
            return Mono.error(new IdempotencyStoreUnavailableException(e));
        }
        log.warn("Idempotency store unavailable; executing unprotected per idempotency.on-store-failure", e);
        return Mono.just(java.util.Optional.empty());
    }

    // --- Execute path -------------------------------------------------------------------------

    private Mono<Object> execute(ProceedingJoinPoint pjp, Method method, String storeKey,
            String fingerprint, Duration ttl) {
        // singleOptional, not a bare flatMap: a handler returning Mono.empty() is a legitimate
        // response with no body, and it must still be recorded as completed. Without lifting the
        // emptiness into a value the completion step would never run for those handlers and the key
        // would sit IN_PROGRESS until its TTL - every later duplicate blocking or 409-ing against a
        // request that actually succeeded.
        return proceedAsMono(pjp)
                .singleOptional()
                .flatMap(result -> completeThen(result.orElse(null), method, storeKey, fingerprint, ttl)
                        .then(Mono.justOrEmpty(result)))
                .onErrorResume(t -> settleFailure(storeKey, fingerprint, ttl, t).then(Mono.error(t)));
    }

    private Mono<Void> completeThen(Object result, Method method, String storeKey, String fingerprint,
            Duration ttl) {
        IdempotencyRecord record = toRecord(result, fingerprint, method);
        if (exceedsMaxPayload(record)) {
            return blocking(() -> {
                store.release(storeKey);
                log.warn("Idempotent response for key {} exceeds idempotency.max-payload-size; not cached",
                        storeKey);
                metrics.executed();
                return true;
            }).then();
        }
        return blocking(() -> {
            store.complete(storeKey, record, ttl);
            metrics.executed();
            return true;
        }).then();
    }

    private Mono<Void> settleFailure(String storeKey, String fingerprint, Duration ttl, Throwable t) {
        if (shouldRelease(t)) {
            return blocking(() -> {
                store.release(storeKey);
                metrics.released();
                return null;
            }).then();
        }
        return blocking(() -> {
            store.complete(storeKey, toErrorRecord(t, fingerprint), ttl);
            return null;
        }).then();
    }

    // --- Replay / conflict path ---------------------------------------------------------------

    private Mono<Object> handleExisting(ProceedingJoinPoint pjp, Method method, IdempotencyRecord record,
            String storeKey, String fingerprint, Duration ttl) {
        if (!fingerprint.isEmpty() && !fingerprint.equals(record.fingerprint())) {
            metrics.fingerprintMismatch();
            return Mono.error(new FingerprintMismatchException());
        }
        if (record.state() == State.COMPLETED) {
            metrics.replayed();
            return replay(record, method);
        }
        if (props.getOnConflict() == ConflictPolicy.FAIL_FAST) {
            metrics.conflict();
            return Mono.error(new IdempotencyConflictException());
        }
        return waitForCompletion(method, storeKey);
    }

    /**
     * WAIT policy. Polls the store until the in-flight request finishes or the timeout expires.
     *
     * <p>Unlike the servlet implementation this occupies no thread while waiting - the delay is a
     * timer, not a sleep - which removes the thread-pool exhaustion the servlet side documents as a
     * limitation. That is the one place the reactive version is meaningfully better.
     */
    private Mono<Object> waitForCompletion(Method method, String storeKey) {
        Duration timeout = props.getWaitTimeout();
        Duration interval = Duration.ofMillis(50);
        Instant deadline = Instant.now().plus(timeout);

        return Mono.defer(() -> blocking(() -> store.find(storeKey)))
                .flatMap(found -> found
                        .filter(r -> r.state() == State.COMPLETED)
                        .map(r -> {
                            metrics.replayedAfterWait();
                            return replay(r, method);
                        })
                        .orElseGet(Mono::empty))
                .repeatWhenEmpty(flux -> flux
                        .delayElements(interval)
                        .takeWhile(i -> Instant.now().isBefore(deadline)))
                .onErrorResume(IllegalStateException.class, e -> {
                    // repeatWhenEmpty signals exhaustion this way rather than completing empty.
                    metrics.waitTimeout();
                    return Mono.error(new IdempotencyConflictException());
                });
    }

    private Mono<Object> replay(IdempotencyRecord record, Method method) {
        Type payloadType = monoPayloadType(method);

        if (ERROR_PAYLOAD_TYPE.equals(record.payloadType())) {
            // Always re-thrown, never returned as a value - including when the handler declares
            // ResponseEntity. WebFlux picks the message writer from the *declared* generic type, so
            // handing a ProblemDetail body to a method declared Mono<ResponseEntity<Map<..>>> fails
            // conversion and renders 500: the replayed 4xx would arrive as a server error, which is
            // the one outcome a retry must never see. Surfacing it as an ErrorResponse makes the
            // framework render the stored status and body directly, whatever the declared type is.
            ProblemDetail body = readProblemDetail(record.payload(), record.status());
            return Mono.error(new ReplayedErrorResponseException(record.status(), body));
        }

        Object body = record.payload() == null ? null : readJson(record.payload(), innerType(payloadType));
        if (isResponseEntity(payloadType)) {
            return Mono.just(ResponseEntity.status(record.status()).body(body));
        }
        return body == null ? Mono.empty() : Mono.just(body);
    }

    // --- Helpers ------------------------------------------------------------------------------

    /**
     * Runs a blocking store call off the event loop.
     *
     * <p>{@code fromCallable} rather than {@code just}: the call must not happen at assembly time,
     * only when subscribed. {@code boundedElastic} is the scheduler Reactor provides precisely for
     * wrapping blocking I/O that cannot be made reactive.
     */
    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> call) {
        return Mono.fromCallable(call).subscribeOn(Schedulers.boundedElastic());
    }

    @SuppressWarnings("unchecked")
    private Mono<Object> proceedAsMono(ProceedingJoinPoint pjp) {
        try {
            Object result = pjp.proceed();
            return result == null ? Mono.empty() : (Mono<Object>) result;
        } catch (Throwable t) {
            return Mono.error(t);
        }
    }

    /** The {@code T} in a declared {@code Mono<T>}. */
    private static Type monoPayloadType(Method method) {
        Type generic = method.getGenericReturnType();
        if (generic instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1) {
            return pt.getActualTypeArguments()[0];
        }
        return Object.class;
    }

    private static boolean isResponseEntity(Type type) {
        if (type instanceof ParameterizedType pt) {
            return pt.getRawType() == ResponseEntity.class;
        }
        return type == ResponseEntity.class;
    }

    /** For {@code Mono<ResponseEntity<T>>} the stored body is the {@code T}, not the entity. */
    private static Type innerType(Type payloadType) {
        if (isResponseEntity(payloadType) && payloadType instanceof ParameterizedType pt
                && pt.getActualTypeArguments().length == 1) {
            return pt.getActualTypeArguments()[0];
        }
        return payloadType;
    }

    private IdempotencyRecord toRecord(Object result, String fingerprint, Method method) {
        int status = 200;
        Object body = result;
        if (result instanceof ResponseEntity<?> re) {
            status = re.getStatusCode().value();
            body = re.getBody();
        }
        return new IdempotencyRecord(State.COMPLETED, fingerprint, status,
                monoPayloadType(method).getTypeName(), writeJson(body), Instant.now());
    }

    private IdempotencyRecord toErrorRecord(Throwable t, String fingerprint) {
        int status = resolveStatus(t);
        Object body = (t instanceof ErrorResponse er) ? er.getBody() : problemDetailFor(status, t.getMessage());
        return new IdempotencyRecord(State.COMPLETED, fingerprint, status, ERROR_PAYLOAD_TYPE,
                writeJson(body), Instant.now());
    }

    private static ProblemDetail problemDetailFor(int status, String message) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        if (message != null) {
            pd.setDetail(message);
        }
        return pd;
    }

    private boolean shouldRelease(Throwable t) {
        return resolveStatus(t) >= 500
                && props.getReleaseOn().contains(IdempotencyProperties.ReleaseOn.FIVE_XX);
    }

    private static int resolveStatus(Throwable t) {
        if (t instanceof ErrorResponse er) {
            return er.getStatusCode().value();
        }
        var ann = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                t.getClass(), org.springframework.web.bind.annotation.ResponseStatus.class);
        return ann != null ? ann.code().value() : HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    private boolean exceedsMaxPayload(IdempotencyRecord record) {
        return record.payload() != null
                && record.payload().length() > props.getMaxPayloadSize().toBytes();
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

    /**
     * Rebuilds the stored error body.
     *
     * <p>Read as a map and reassembled rather than deserialized straight into {@link ProblemDetail}:
     * that type is built for writing, not reading, and Jackson cannot reliably reconstruct it -
     * which turned every replayed 4xx into a 500 until this was changed. Going through a map also
     * preserves any extension members the original carried.
     */
    private ProblemDetail readProblemDetail(String json, int status) {
        ProblemDetail detail = ProblemDetail.forStatus(status);
        if (json == null) {
            return detail;
        }
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = payloadMapper.readValue(json, java.util.Map.class);
            Object title = map.remove("title");
            Object det = map.remove("detail");
            Object instance = map.remove("instance");
            Object type = map.remove("type");
            map.remove("status");
            if (title != null) {
                detail.setTitle(String.valueOf(title));
            }
            if (det != null) {
                detail.setDetail(String.valueOf(det));
            }
            if (instance != null) {
                detail.setInstance(java.net.URI.create(String.valueOf(instance)));
            }
            if (type != null) {
                detail.setType(java.net.URI.create(String.valueOf(type)));
            }
            map.forEach(detail::setProperty);
            return detail;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize stored idempotent error response", e);
        }
    }

    private Object readJson(String json, Type type) {
        try {
            JavaType javaType = payloadMapper.getTypeFactory().constructType(type);
            return payloadMapper.readValue(json, javaType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize stored idempotent response", e);
        }
    }
}
