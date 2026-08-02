package io.github.benhendayoussef.idempotency.internal;

import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-instance, non-durable store. Used as the {@code idempotency.store=memory} fallback and
 * in tests; never appropriate across more than one instance since state lives in local heap.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Entry(IdempotencyRecord record, Instant expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public ClaimResult claim(String key, String fingerprint, Duration ttl) {
        Instant now = Instant.now();
        var candidate = new Entry(IdempotencyRecord.inProgress(fingerprint, now), now.plus(ttl));

        Entry[] winner = new Entry[1];
        entries.compute(key, (k, existing) -> {
            if (existing == null || existing.expiresAt().isBefore(now)) {
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
        entries.put(key, new Entry(record, Instant.now().plus(ttl)));
    }

    @Override
    public void release(String key) {
        entries.remove(key);
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        Entry entry = entries.get(key);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(entry.record());
    }
}
