package io.github.benhendayoussef.idempotency.api;

/**
 * Thrown when a request is still {@code IN_PROGRESS} and the effective {@link ConflictPolicy}
 * is {@code FAIL_FAST}, or a {@code WAIT} poll exceeds its timeout. Maps to 409.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("A request with this idempotency key is already in progress");
    }
}
