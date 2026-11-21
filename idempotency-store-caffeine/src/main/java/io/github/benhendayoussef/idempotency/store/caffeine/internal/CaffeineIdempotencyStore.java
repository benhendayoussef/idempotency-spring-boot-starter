package io.github.benhendayoussef.idempotency.store.caffeine.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Single-instance store with real eviction.
 *
 * <p>The built-in {@code InMemoryIdempotencyStore} is correct but unbounded: expired entries are
 * <em>treated</em> as absent and never actually removed, so a long-running service accumulates one
 * dead entry per idempotency key it has ever seen. That is fine for tests, which is what it was
 * written for, and a slow leak in anything that stays up. This store keeps the same semantics and
 * adds the two things that make it safe to leave running: per-entry expiry, so records go away when
 * their TTL passes, and a maximum size, so memory has a ceiling even if traffic outruns expiry.
 *
 * <p>Still single-instance and still non-durable - state lives in local heap, so two application
 * instances do not share it and a restart forgets everything. For anything replicated, use Redis or
 * JDBC.
 *
 * <p>Public only because Spring auto-configuration in another module constructs it;
 * <strong>not part of the supported API</strong>.
 */
public class CaffeineIdempotencyStore implements IdempotencyStore {

    /**
     * The expiry deadline is carried on the entry rather than being a fixed cache-wide duration,
     * because TTL is per-record here: {@code @Idempotent(ttl = ...)} lets it vary per endpoint, and
     * {@code complete()} re-stamps it.
     *
     * <p>{@code owner} is {@code null} for a claim made without one, which any caller may then
     * settle.
     */
    private record Entry(IdempotencyRecord record, Instant expiresAt, String owner) {

        boolean ownedBy(String caller) {
            return owner == null || owner.equals(caller);
        }
    }

    private final Cache<String, Entry> cache;

    public CaffeineIdempotencyStore(long maximumSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfter(new Expiry<String, Entry>() {
                    @Override
                    public long expireAfterCreate(String key, Entry value, long currentTime) {
                        return nanosUntil(value.expiresAt());
                    }

                    @Override
                    public long expireAfterUpdate(String key, Entry value, long currentTime,
                            long currentDuration) {
                        // Not currentDuration: complete() replaces an IN_PROGRESS entry with the
                        // finished record and a fresh TTL, so keeping the remaining lifetime of the
                        // claim would expire the stored response early - exactly when a replay needs
                        // it most.
                        return nanosUntil(value.expiresAt());
                    }

                    @Override
                    public long expireAfterRead(String key, Entry value, long currentTime,
                            long currentDuration) {
                        // Reading must not extend the life of a record. Idempotency windows are
                        // absolute: a key is valid for its TTL from when it was claimed, however
                        // often it is replayed in the meantime.
                        return currentDuration;
                    }
                })
                .build();
    }

    private static long nanosUntil(Instant deadline) {
        long nanos = Duration.between(Instant.now(), deadline).toNanos();
        // Caffeine rejects a negative duration; an already-expired entry becomes immediately
        // evictable instead.
        return Math.max(nanos, 0L);
    }

    @Override
    public ClaimResult claim(String key, String fingerprint, Duration ttl) {
        return claim(key, fingerprint, ttl, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Atomic via {@code asMap().compute()}, which Caffeine performs under the entry's lock - the
     * same guarantee {@code ConcurrentHashMap} gives, and the reason two concurrent duplicates
     * cannot both be told they acquired the key.
     *
     * <p>The expiry check is still made explicitly here rather than relying on eviction. Eviction is
     * asynchronous: an entry past its TTL can still be present for a short window, and treating it
     * as live would mean a caller conflicts with a record that should already be gone.
     */
    @Override
    public ClaimResult claim(String key, String fingerprint, Duration ttl, String owner) {
        Instant now = Instant.now();
        var candidate = new Entry(IdempotencyRecord.inProgress(fingerprint, now), now.plus(ttl), owner);

        Entry[] winner = new Entry[1];
        cache.asMap().compute(key, (k, existing) -> {
            if (!live(existing, now)) {
                winner[0] = candidate;
                return candidate;
            }
            winner[0] = existing;
            return existing;
        });

        if (winner[0] == candidate) {
            return new ClaimResult.Acquired();
        }
        return new ClaimResult.AlreadyHeld(winner[0].record());
    }

    @Override
    public void complete(String key, IdempotencyRecord record, Duration ttl) {
        cache.put(key, new Entry(record, Instant.now().plus(ttl), null));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The owner check and the write happen under the entry's lock in {@code asMap().compute()},
     * the same guarantee {@link #claim} relies on. An entry past its TTL counts as absent: nobody
     * holds that key, so the response is still this run's to store.
     */
    @Override
    public boolean complete(String key, String owner, IdempotencyRecord record, Duration ttl) {
        Objects.requireNonNull(owner, "owner");
        Instant now = Instant.now();
        boolean[] applied = new boolean[1];
        cache.asMap().compute(key, (k, existing) -> {
            if (live(existing, now) && !existing.ownedBy(owner)) {
                return existing;
            }
            applied[0] = true;
            return new Entry(record, now.plus(ttl), owner);
        });
        return applied[0];
    }

    @Override
    public void release(String key) {
        cache.invalidate(key);
    }

    @Override
    public boolean release(String key, String owner) {
        Objects.requireNonNull(owner, "owner");
        Instant now = Instant.now();
        boolean[] applied = new boolean[1];
        cache.asMap().compute(key, (k, existing) -> {
            if (live(existing, now) && !existing.ownedBy(owner)) {
                return existing;
            }
            applied[0] = true;
            return null;
        });
        return applied[0];
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        Entry entry = cache.getIfPresent(key);
        return live(entry, Instant.now()) ? Optional.of(entry.record()) : Optional.empty();
    }

    private static boolean live(Entry entry, Instant now) {
        return entry != null && !entry.expiresAt().isBefore(now);
    }

    /** Entries currently held, after any pending eviction work. Exposed for tests and diagnostics. */
    public long estimatedSize() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    /** Package-private hook so tests can assert eviction without sleeping for a real TTL. */
    void runPendingEviction() {
        cache.cleanUp();
    }
}
