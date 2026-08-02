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
}
