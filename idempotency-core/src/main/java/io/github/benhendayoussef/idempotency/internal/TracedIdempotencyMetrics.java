package io.github.benhendayoussef.idempotency.internal;

import io.github.benhendayoussef.idempotency.api.IdempotencyMetrics;
import io.github.benhendayoussef.idempotency.api.IdempotencyTracer;

/**
 * Sends every outcome to the trace as well as to the metrics implementation.
 *
 * <p>A decorator rather than twelve extra call sites: the servlet aspect, the reactive aspect and
 * the filter all already report every outcome through {@link IdempotencyMetrics}, so wrapping that
 * one collaborator traces all three execution paths and leaves no site where a later outcome can be
 * counted but not traced. It also keeps the two concerns replaceable independently - an application
 * that supplies its own {@code IdempotencyMetrics} bean still gets tracing, and vice versa.
 *
 * <p>Not part of the supported API; see the package documentation.
 */
public final class TracedIdempotencyMetrics implements IdempotencyMetrics {

    private final IdempotencyMetrics delegate;
    private final IdempotencyTracer tracer;

    private TracedIdempotencyMetrics(IdempotencyMetrics delegate, IdempotencyTracer tracer) {
        this.delegate = delegate;
        this.tracer = tracer;
    }

    /**
     * Returns {@code metrics} unchanged when there is nothing to trace to, so the request path keeps
     * the shape it had before tracing existed rather than paying for a decorator that does nothing.
     */
    public static IdempotencyMetrics wrap(IdempotencyMetrics metrics, IdempotencyTracer tracer) {
        return tracer == null || tracer == IdempotencyTracer.NONE
                ? metrics
                : new TracedIdempotencyMetrics(metrics, tracer);
    }

    @Override
    public void executed() {
        delegate.executed();
        tracer.outcome("executed");
    }

    @Override
    public void replayed() {
        delegate.replayed();
        tracer.outcome("replayed");
    }

    @Override
    public void replayedAfterWait() {
        delegate.replayedAfterWait();
        tracer.outcome("replayed_after_wait");
    }

    @Override
    public void released() {
        delegate.released();
        tracer.outcome("released");
    }

    @Override
    public void conflict() {
        delegate.conflict();
        tracer.outcome("conflict");
    }

    @Override
    public void waitTimeout() {
        delegate.waitTimeout();
        tracer.outcome("wait_timeout");
    }

    @Override
    public void fingerprintMismatch() {
        delegate.fingerprintMismatch();
        tracer.outcome("fingerprint_mismatch");
    }

    @Override
    public void missingKey() {
        delegate.missingKey();
        tracer.outcome("missing_key");
    }

    @Override
    public void storeFailure() {
        delegate.storeFailure();
        tracer.outcome("store_failure");
    }

    @Override
    public void principalMissing() {
        delegate.principalMissing();
        tracer.outcome("principal_missing");
    }
}
