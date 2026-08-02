package io.github.benhendayoussef.idempotency.api;

/**
 * Thrown when the configured {@link IdempotencyStore} is unreachable and
 * {@code idempotency.on-store-failure=fail}. Maps to 503. The default,
 * {@code on-store-failure=proceed}, executes the handler unprotected instead of throwing this.
 */
public class IdempotencyStoreUnavailableException extends RuntimeException {

    public IdempotencyStoreUnavailableException(Throwable cause) {
        super("IdempotencyStore is unavailable", cause);
    }
}
