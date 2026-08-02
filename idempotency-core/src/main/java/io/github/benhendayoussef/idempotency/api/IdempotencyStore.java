package io.github.benhendayoussef.idempotency.api;

import java.time.Duration;
import java.util.Optional;

/**
 * The extension point for persisting idempotency records. Implement this to back the starter
 * with any store (DynamoDB, Hazelcast, ...) beyond the Redis and JDBC implementations shipped
 * out of the box.
 */
public interface IdempotencyStore {

    /**
     * Atomically claim the key, or report the record that already holds it.
     * Implementations MUST be atomic across concurrent callers.
     */
    ClaimResult claim(String key, String fingerprint, Duration ttl);

    /** Transition to COMPLETED. Called only by the thread that won the claim. */
    void complete(String key, IdempotencyRecord record, Duration ttl);

    /** Drop the claim so a retry can re-execute. */
    void release(String key);

    Optional<IdempotencyRecord> find(String key);

    sealed interface ClaimResult {
        record Acquired() implements ClaimResult {}
        record AlreadyHeld(IdempotencyRecord record) implements ClaimResult {}
    }
}
