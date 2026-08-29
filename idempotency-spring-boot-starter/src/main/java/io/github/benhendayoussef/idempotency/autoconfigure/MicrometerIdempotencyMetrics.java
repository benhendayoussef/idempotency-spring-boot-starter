package io.github.benhendayoussef.idempotency.autoconfigure;

import io.github.benhendayoussef.idempotency.api.IdempotencyMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer-backed {@link IdempotencyMetrics}, registered automatically when Micrometer and a
 * {@code MeterRegistry} are both present.
 *
 * <p>Everything is recorded on <strong>one</strong> counter, {@code idempotency.requests},
 * distinguished by an {@code outcome} tag rather than by ten separate meter names. That is the
 * difference between being able to ask "what fraction of requests were replays?" as a single ratio
 * and having to hard-code a list of metric names into every dashboard. It also means a new outcome
 * added later shows up in existing queries instead of being invisible until someone updates them.
 *
 * <p>Package-private on purpose: this adds no public API. An application that wants different
 * meters registers its own {@link IdempotencyMetrics} bean, which the starter backs off from.
 */
final class MicrometerIdempotencyMetrics implements IdempotencyMetrics {

    private static final String COUNTER = "idempotency.requests";
    private static final String DESCRIPTION =
            "Requests handled by @Idempotent, tagged by what the aspect did with them";

    // Resolved once, not per call. registry.counter(name, tags) is a map lookup plus tag-list
    // construction on every invocation, and these sit on the request hot path - the aspect calls one
    // of them for every single request it advises.
    private final Counter executed;
    private final Counter replayed;
    private final Counter replayedAfterWait;
    private final Counter released;
    private final Counter conflict;
    private final Counter waitTimeout;
    private final Counter fingerprintMismatch;
    private final Counter missingKey;
    private final Counter storeFailure;
    private final Counter principalMissing;

    MicrometerIdempotencyMetrics(MeterRegistry registry) {
        this.executed = counter(registry, "executed");
        this.replayed = counter(registry, "replayed");
        this.replayedAfterWait = counter(registry, "replayed_after_wait");
        this.released = counter(registry, "released");
        this.conflict = counter(registry, "conflict");
        this.waitTimeout = counter(registry, "wait_timeout");
        this.fingerprintMismatch = counter(registry, "fingerprint_mismatch");
        this.missingKey = counter(registry, "missing_key");
        this.storeFailure = counter(registry, "store_failure");
        this.principalMissing = counter(registry, "principal_missing");
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder(COUNTER)
                .description(DESCRIPTION)
                .tag("outcome", outcome)
                .register(registry);
    }

    @Override
    public void executed() {
        executed.increment();
    }

    @Override
    public void replayed() {
        replayed.increment();
    }

    @Override
    public void replayedAfterWait() {
        replayedAfterWait.increment();
    }

    @Override
    public void released() {
        released.increment();
    }

    @Override
    public void conflict() {
        conflict.increment();
    }

    @Override
    public void waitTimeout() {
        waitTimeout.increment();
    }

    @Override
    public void fingerprintMismatch() {
        fingerprintMismatch.increment();
    }

    @Override
    public void missingKey() {
        missingKey.increment();
    }

    @Override
    public void storeFailure() {
        storeFailure.increment();
    }

    @Override
    public void principalMissing() {
        principalMissing.increment();
    }
}
