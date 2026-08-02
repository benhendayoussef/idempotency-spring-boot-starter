package io.github.benhendayoussef.idempotency.api;

/**
 * Thrown at startup when {@code @Idempotent} is on the classpath but no {@link IdempotencyStore}
 * bean could be configured (no Redis, no JDBC store, and {@code idempotency.store} not set to
 * {@code memory}).
 */
public class NoStoreConfiguredException extends RuntimeException {

    public NoStoreConfiguredException() {
        super("No IdempotencyStore is configured");
    }
}
