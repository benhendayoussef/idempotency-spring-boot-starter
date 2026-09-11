package io.github.benhendayoussef.idempotency.autoconfigure;

import io.github.benhendayoussef.idempotency.api.IdempotencyTracer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * Tags the request's existing span with what the library did, rather than opening a span of its own.
 *
 * <p>A child span would be the obvious move and is the wrong one here. The question an operator
 * arrives with is "why did this request return in two milliseconds and write nothing?", and they ask
 * it while looking at the server span - so that is where the answer has to be. A separate
 * {@code idempotency} span would put the answer one level down, add a span to every request in the
 * system to carry a single string, and still leave the server span looking inexplicable to anyone
 * scanning a trace list.
 *
 * <p>Package-private: this adds no public API. An application wanting different behaviour publishes
 * its own {@link IdempotencyTracer} bean, which the starter backs off from.
 */
final class MicrometerIdempotencyTracer implements IdempotencyTracer {

    /** Deliberately the {@code outcome} tag name the metrics counter uses, under an idempotency prefix. */
    static final String TAG = "idempotency.outcome";

    private final Tracer tracer;

    MicrometerIdempotencyTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void outcome(String outcome) {
        // Null whenever the request is not sampled, which is most of them under any realistic
        // sampling rate. Not an error condition - just nothing to write to.
        Span span = tracer.currentSpan();
        if (span != null) {
            span.tag(TAG, outcome);
        }
    }
}
