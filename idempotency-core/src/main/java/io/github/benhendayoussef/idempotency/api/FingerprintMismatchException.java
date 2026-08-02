package io.github.benhendayoussef.idempotency.api;

/**
 * Thrown when the same idempotency key is reused with a different argument fingerprint —
 * a client bug, never a silent replay. Maps to 422.
 */
public class FingerprintMismatchException extends RuntimeException {

    public FingerprintMismatchException() {
        super("Idempotency key reused with a different request payload");
    }
}
