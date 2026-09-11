package io.github.benhendayoussef.idempotency.internal.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.benhendayoussef.idempotency.api.ConflictPolicy;
import io.github.benhendayoussef.idempotency.api.IdempotencyMetrics;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord.State;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore.ClaimResult;
import io.github.benhendayoussef.idempotency.api.Idempotent;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * {@code idempotency.mode=filter}: claim, replay and completion at the HTTP layer, storing the
 * response byte for byte.
 *
 * <h2>Why this cannot be done in the aspect</h2>
 *
 * <p>The aspect runs <em>inside</em> the handler invocation, and the response body does not exist
 * yet when it returns - message conversion happens afterwards. So aspect mode stores the handler's
 * return value and re-serializes it on replay, which is why it cannot capture anything written
 * directly to the {@code HttpServletResponse}, a header added further down the chain, or a content
 * type negotiated at write time. Capturing real bytes means owning the request from outside the
 * whole dispatch, which is what a filter is.
 *
 * <p>The two modes are therefore mutually exclusive: when this filter is active the aspect is not
 * registered at all. Running both would have each claim the same key for the same request.
 *
 * <h2>Deciding what to protect</h2>
 *
 * <p>Selection stays annotation-driven - {@code @Idempotent} on the handler method, exactly as in
 * aspect mode - rather than becoming "every POST with a key header". Two modes of the same library
 * disagreeing about <em>which</em> endpoints are idempotent would be a far worse surprise than any
 * difference in how they store the response.
 *
 * <p>That requires knowing the handler before running it, so the request is resolved against the
 * application's {@link HandlerMapping}s up front. Spring will resolve it again during the actual
 * dispatch; the cost is one extra mapping lookup per request that carries an idempotency key.
 */
public class IdempotencyFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    /** Distinguishes a raw captured response from an aspect-mode serialized return value. */
    public static final String RAW_PAYLOAD_TYPE = "!raw";

    private final IdempotencyStore store;
    private final IdempotencyProperties props;
    private final ObjectMapper payloadMapper;
    private final IdempotencyMetrics metrics;
    private final List<HandlerMapping> handlerMappings;

    public IdempotencyFilter(IdempotencyStore store, IdempotencyProperties props, ObjectMapper payloadMapper,
            IdempotencyMetrics metrics, List<HandlerMapping> handlerMappings) {
        this.store = store;
        this.props = props;
        this.payloadMapper = payloadMapper;
        this.metrics = metrics;
        this.handlerMappings = handlerMappings;
    }

    /**
     * Runs late enough that Spring Security has already authenticated - a 401 must never claim a key
     * on behalf of a caller who was then rejected - but still outside the dispatcher.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        Idempotent annotation = resolveAnnotation(request);
        if (annotation == null) {
            chain.doFilter(request, response);
            return;
        }

        String headerName = annotation.keyHeader().isBlank() ? props.getHeaderName() : annotation.keyHeader();
        String clientKey = request.getHeader(headerName);
        if (clientKey == null || clientKey.isBlank()) {
            metrics.missingKey();
            if (props.isRequireKey()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "Missing required " + headerName + " header");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        String storeKey = FilterKeyComposer.compose(clientKey, "", request);
        Duration ttl = annotation.ttl().isBlank() ? props.getDefaultTtl()
                : Duration.parse("PT" + annotation.ttl());
        // Argument fingerprinting is an aspect-mode feature: it hashes the resolved method arguments,
        // which do not exist yet out here. The request body would be the equivalent, and reading it
        // in a filter means buffering every request - a real cost imposed on every endpoint to serve
        // one. Documented as a difference between the modes rather than silently approximated.
        String fingerprint = "";

        ClaimResult claim;
        try {
            claim = store.claim(storeKey, fingerprint, props.claimTtlFor(ttl));
        } catch (RuntimeException e) {
            metrics.storeFailure();
            if (props.getOnStoreFailure() == IdempotencyProperties.OnStoreFailure.FAIL) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Idempotency store unavailable");
                return;
            }
            log.warn("Idempotency store unavailable; executing unprotected per idempotency.on-store-failure", e);
            chain.doFilter(request, response);
            return;
        }

        if (claim instanceof ClaimResult.AlreadyHeld held) {
            handleExisting(request, response, chain, held.record(), storeKey);
            return;
        }
        execute(request, response, chain, storeKey, ttl);
    }

    private void execute(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
            String storeKey, Duration ttl) throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        boolean completed = false;
        try {
            chain.doFilter(request, wrapper);

            CapturedResponse captured = capture(wrapper);
            if (shouldRelease(captured.status())) {
                store.release(storeKey);
                metrics.released();
            } else {
                String json = payloadMapper.writeValueAsString(captured);
                if (json.length() > props.getMaxPayloadSize().toBytes()) {
                    store.release(storeKey);
                    log.warn("Idempotent response for key {} exceeds idempotency.max-payload-size; not cached",
                            storeKey);
                } else {
                    store.complete(storeKey, new IdempotencyRecord(State.COMPLETED, "", captured.status(),
                            RAW_PAYLOAD_TYPE, json, Instant.now()), ttl);
                }
                metrics.executed();
            }
            completed = true;
        } finally {
            if (!completed) {
                // The chain threw before a status was ever produced, so there is nothing to store and
                // nothing to replay. Release rather than leaving the key IN_PROGRESS for its whole
                // TTL, which would make every retry conflict against a request that never finished.
                releaseQuietly(storeKey);
            }
            // Must happen on every path: the cached body is buffered and never reaches the client
            // until it is copied out. Skipping this on the error path would turn a handler exception
            // into an empty response.
            wrapper.copyBodyToResponse();
        }
    }

    private void handleExisting(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
            IdempotencyRecord record, String storeKey) throws ServletException, IOException {
        if (record.state() == State.COMPLETED) {
            metrics.replayed();
            writeReplay(response, record, false);
            return;
        }
        if (props.getOnConflict() == ConflictPolicy.FAIL_FAST) {
            metrics.conflict();
            sendConflict(response);
            return;
        }
        Optional<IdempotencyRecord> finished = waitForCompletion(storeKey);
        if (finished.isPresent()) {
            metrics.replayedAfterWait();
            writeReplay(response, finished.get(), true);
            return;
        }
        metrics.waitTimeout();
        sendConflict(response);
    }

    private Optional<IdempotencyRecord> waitForCompletion(String storeKey) {
        Instant deadline = Instant.now().plus(props.getWaitTimeout());
        while (Instant.now().isBefore(deadline)) {
            Optional<IdempotencyRecord> found = store.find(storeKey);
            if (found.isPresent() && found.get().state() == State.COMPLETED) {
                return found;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Writes the stored response back exactly as it was sent, headers and bytes included. */
    private void writeReplay(HttpServletResponse response, IdempotencyRecord record, boolean afterWait)
            throws IOException {
        CapturedResponse captured = payloadMapper.readValue(record.payload(), CapturedResponse.class);
        response.setStatus(captured.status());
        captured.headers().forEach((name, values) -> values.forEach(v -> response.addHeader(name, v)));
        response.setHeader("Idempotent-Replay", "true");
        byte[] body = Base64.getDecoder().decode(captured.body());
        if (body.length > 0) {
            // Content-Length is set explicitly rather than left to the container: the replayed body
            // may differ in length from whatever a stale stored header claimed.
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
        }
        response.flushBuffer();
        if (afterWait) {
            log.debug("Replayed idempotent response after waiting for the in-flight request");
        }
    }

    private CapturedResponse capture(ContentCachingResponseWrapper wrapper) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        List<String> allowed = props.getFilter().getReplayHeaders();
        for (String name : wrapper.getHeaderNames()) {
            if (allowed.stream().anyMatch(a -> a.equalsIgnoreCase(name))) {
                headers.put(name, new ArrayList<>(wrapper.getHeaders(name)));
            }
        }
        String body = Base64.getEncoder().encodeToString(wrapper.getContentAsByteArray());
        return new CapturedResponse(wrapper.getStatus(), headers, body);
    }

    private boolean shouldRelease(int status) {
        return status >= 500 && props.getReleaseOn().contains(IdempotencyProperties.ReleaseOn.FIVE_XX);
    }

    private void sendConflict(HttpServletResponse response) throws IOException {
        response.setHeader("Retry-After", String.valueOf(Math.max(1, props.getWaitTimeout().toSeconds())));
        response.sendError(HttpServletResponse.SC_CONFLICT, "A request with this idempotency key is in progress");
    }

    private void releaseQuietly(String storeKey) {
        try {
            store.release(storeKey);
        } catch (RuntimeException e) {
            log.warn("Failed to release idempotency key {} after a failed request; it will expire with its TTL",
                    storeKey, e);
        }
    }

    /**
     * Finds {@code @Idempotent} on the handler this request would dispatch to, without dispatching.
     *
     * <p>Returns null - meaning "not our business" - for anything unmapped, any non-handler-method
     * target such as a static resource, and any exception during resolution. A filter that failed
     * requests because it could not work out whether they were idempotent would be far worse than
     * one that occasionally declines to protect a request.
     */
    private Idempotent resolveAnnotation(HttpServletRequest request) {
        for (HandlerMapping mapping : handlerMappings) {
            try {
                HandlerExecutionChain chain = mapping.getHandler(request);
                if (chain == null) {
                    continue;
                }
                if (chain.getHandler() instanceof HandlerMethod handlerMethod) {
                    return handlerMethod.getMethodAnnotation(Idempotent.class);
                }
                return null;
            } catch (Exception e) {
                log.debug("Handler resolution failed while checking for @Idempotent; "
                        + "treating the request as not idempotent", e);
                return null;
            }
        }
        return null;
    }
}
