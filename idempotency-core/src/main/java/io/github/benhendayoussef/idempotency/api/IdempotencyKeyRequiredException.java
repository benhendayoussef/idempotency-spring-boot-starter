package io.github.benhendayoussef.idempotency.api;

/** Thrown when {@code idempotency.require-key=true} and the request has no key header. Maps to 400. */
public class IdempotencyKeyRequiredException extends RuntimeException {

    public IdempotencyKeyRequiredException() {
        super("Missing required idempotency key header");
    }
}
