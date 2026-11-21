package io.github.benhendayoussef.idempotency.api;

/**
 * Observability hook for the aspect. Every method has a no-op default so implementors only
 * override the counters they care about; wire this to Micrometer (or anything else) with a
 * {@code @Bean} of your own — the starter backs off via {@code @ConditionalOnMissingBean}.
 */
public interface IdempotencyMetrics {

    default void executed() {
    }

    default void replayed() {
    }

    default void replayedAfterWait() {
    }

    default void released() {
    }

    default void conflict() {
    }

    default void waitTimeout() {
    }

    default void fingerprintMismatch() {
    }

    default void missingKey() {
    }

    default void storeFailure() {
    }

    default void principalMissing() {
    }

    /**
     * The handler finished, but its claim had expired and the key was no longer its own, so its
     * completion or release was skipped. Counted once per run, alongside that run's other outcome.
     * Any non-zero rate means {@code idempotency.claim-ttl} is shorter than the slowest handler.
     */
    default void lostClaim() {
    }
}
