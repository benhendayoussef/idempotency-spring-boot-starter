package io.github.benhendayoussef.idempotency.internal;

import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-instance, non-durable store. Used as the {@code idempotency.store=memory} fallback and
 * in tests; never appropriate across more than one instance since state lives in local heap.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    /** {@code owner} is {@code null} for a claim made without one, which any caller may then settle. */
    private record Entry(IdempotencyRecord record, Instant expiresAt, String owner) {

        boolean ownedBy(String caller) {
            return owner == null || owner.equals(caller);
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public ClaimResult claim(String key, String fingerprint, Duration ttl) {
        return claim(key, fingerprint, ttl, null);
    }

    @Override
    public ClaimResult claim(String key, String fingerprint, Duration ttl, String owner) {
        Instant now = Instant.now();
        var candidate = new Entry(IdempotencyRecord.inProgress(fingerprint, now), now.plus(ttl), owner);

        Entry[] winner = new Entry[1];
        entries.compute(key, (k, existing) -> {
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
        entries.put(key, new Entry(record, Instant.now().plus(ttl), null));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The owner check and the write happen under the entry's lock in {@code compute}, so a
     * concurrent reclaim cannot slip in between them. An entry past its TTL counts as absent, as it
     * does for {@link #claim}: nobody holds that key, so the response is still this run's to store.
     */
    @Override
    public boolean complete(String key, String owner, IdempotencyRecord record, Duration ttl) {
        Objects.requireNonNull(owner, "owner");
        Instant now = Instant.now();
        boolean[] applied = new boolean[1];
        entries.compute(key, (k, existing) -> {
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
        entries.remove(key);
    }

    @Override
    public boolean release(String key, String owner) {
        Objects.requireNonNull(owner, "owner");
        Instant now = Instant.now();
        boolean[] applied = new boolean[1];
        entries.compute(key, (k, existing) -> {
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
        Entry entry = entries.get(key);
        return live(entry, Instant.now()) ? Optional.of(entry.record()) : Optional.empty();
    }

    private static boolean live(Entry entry, Instant now) {
        return entry != null && !entry.expiresAt().isBefore(now);
    }
}
