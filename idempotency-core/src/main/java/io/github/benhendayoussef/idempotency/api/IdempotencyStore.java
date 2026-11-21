package io.github.benhendayoussef.idempotency.api;

import java.time.Duration;
import java.util.Optional;

/**
 * The extension point for persisting idempotency records. Implement this to back the starter
 * with any store (DynamoDB, Hazelcast, ...) beyond the Redis and JDBC implementations shipped
 * out of the box.
 *
 * <h2>Claim ownership</h2>
 *
 * <p>A claim is a lease: it can expire while its handler is still running, and another request can
 * then reclaim the key. When the first run finishes it must not write to a key it no longer owns -
 * a late {@code complete()} would overwrite the response the second run already stored, and a late
 * {@code release()} would delete the second run's claim and let a third run execute.
 *
 * <p>The starter therefore tags every claim with an opaque {@code owner} token, unique per claim,
 * and passes it to the owner-aware overloads of {@link #claim(String, String, Duration, String)
 * claim}, {@link #complete(String, String, IdempotencyRecord, Duration) complete} and
 * {@link #release(String, String) release}. A store that keeps the token makes the last two
 * conditional on it. Those overloads have defaults that ignore the token and delegate to the
 * original methods, so an implementation written before they existed keeps compiling and behaves
 * exactly as it always did - it simply does not get the protection.
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

    /**
     * {@link #claim(String, String, Duration)}, recording {@code owner} alongside the claim when it
     * is acquired. {@code owner} is never {@code null} and is unique to this claim attempt.
     *
     * <p>The default ignores {@code owner}.
     */
    default ClaimResult claim(String key, String fingerprint, Duration ttl, String owner) {
        return claim(key, fingerprint, ttl);
    }

    /**
     * {@link #complete(String, IdempotencyRecord, Duration)}, skipped if the key is now held by a
     * <em>different</em> owner.
     *
     * <p>Only another owner's live claim or record blocks the write. A claim that expired without
     * being reclaimed is still this caller's to complete, exactly as before owners existed - a
     * handler slightly slower than {@code claim-ttl} with no competing request must not lose its
     * response. A record with no owner recorded (written by a version of this library that did not
     * track owners) counts as held by any caller.
     *
     * <p>The default ignores {@code owner}, completes unconditionally and returns {@code true}.
     *
     * @return {@code false} if nothing was written, normally because another owner holds the key
     */
    default boolean complete(String key, String owner, IdempotencyRecord record, Duration ttl) {
        complete(key, record, ttl);
        return true;
    }

    /**
     * {@link #release(String)}, skipped if the key is now held by a <em>different</em> owner. The
     * same rules as {@link #complete(String, String, IdempotencyRecord, Duration)} decide who holds
     * it; a key that is already gone counts as released.
     *
     * <p>The default ignores {@code owner}, releases unconditionally and returns {@code true}.
     *
     * @return {@code false} if another owner holds the key, in which case nothing was deleted
     */
    default boolean release(String key, String owner) {
        release(key);
        return true;
    }

    sealed interface ClaimResult {
        record Acquired() implements ClaimResult {}
        record AlreadyHeld(IdempotencyRecord record) implements ClaimResult {}
    }
}
