package io.github.benhendayoussef.idempotency.api;

/**
 * Records on the in-flight trace what {@code @Idempotent} did with a request.
 *
 * <p>This exists because a replay is invisible in a trace otherwise. The span says the endpoint was
 * called and returned 201 in two milliseconds, having touched no database and made no downstream
 * call - which looks like a bug, or like the handler silently did nothing. The outcome tag is the
 * difference between that and "this was a replay of an earlier request".
 *
 * <p>The values passed here are exactly the {@code outcome} tag values on the
 * {@code idempotency.requests} counter, so a rate on the metric and a filter on the trace select the
 * same population. Adding a new outcome later changes this string set; treat it as open.
 *
 * <p>The starter wires a Micrometer Tracing implementation when the application has tracing
 * configured. Implement this and publish it as a bean to send the outcome somewhere else - an
 * OpenTelemetry span attribute, a log MDC entry, an audit trail. Implementations are called on the
 * request thread, once per request, and must not throw: failing to annotate a trace is never a
 * reason to fail a request.
 */
public interface IdempotencyTracer {

    /** Does nothing. The default when the application has no tracing. */
    IdempotencyTracer NONE = outcome -> {
    };

    /**
     * @param outcome what the library did, e.g. {@code executed}, {@code replayed},
     *                {@code replayed_after_wait}, {@code released}, {@code conflict},
     *                {@code wait_timeout}, {@code fingerprint_mismatch}, {@code missing_key},
     *                {@code store_failure}, {@code principal_missing}
     */
    void outcome(String outcome);
}
