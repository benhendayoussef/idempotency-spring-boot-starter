package io.github.benhendayoussef.idempotency.store.redis.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis-backed store: fast, at-least-once (see the durability caveat in the README). The atomic
 * claim is a single {@code SET NX} round trip; no locks.
 *
 * <p><strong>Claim ownership.</strong> The owner of a claim is kept in a key of its own beside the
 * record, with the same TTL, rather than inside the record: the stored record format is covered by
 * the API stability policy, and a 1.1 instance reading a record with an extra field would fail to
 * deserialize it mid rolling upgrade. Every write that touches both keys is a Lua script, so the
 * pair never disagrees. A record with no owner key - written by 1.1, or by an ownerless claim - is
 * unowned and anyone may settle it.
 *
 * <p>The owner key carries a hash tag so it lands in the record's Redis Cluster slot; a script
 * spanning two slots would be refused with {@code CROSSSLOT}. See {@link #ownerKey(String)}.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final int CLAIM_RETRY_ATTEMPTS = 3;

    // KEYS: record, owner. ARGV: record json, ttl millis, owner ("" for none).
    private static final RedisScript<Long> CLAIM = RedisScript.of("""
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
                if ARGV[3] == '' then
                    redis.call('DEL', KEYS[2])
                else
                    redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[2])
                end
                return 1
            end
            return 0
            """, Long.class);

    // A different owner blocks the write only while the record it owns still exists: an owner key
    // whose record is gone (released by a 1.1 instance) holds nothing.
    private static final String HELD_BY_ANOTHER = """
            local holder = redis.call('GET', KEYS[2])
            if holder and holder ~= ARGV[%d] and redis.call('EXISTS', KEYS[1]) == 1 then
                return 0
            end
            """;

    // KEYS: record, owner. ARGV: record json, ttl millis, owner ("" for an unowned record).
    private static final RedisScript<Long> COMPLETE = RedisScript.of(HELD_BY_ANOTHER.formatted(3) + """
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            if ARGV[3] == '' then
                redis.call('DEL', KEYS[2])
            else
                redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[2])
            end
            return 1
            """, Long.class);

    // KEYS: record, owner. ARGV: owner. A key that is already gone counts as released.
    private static final RedisScript<Long> RELEASE = RedisScript.of(HELD_BY_ANOTHER.formatted(1) + """
            redis.call('DEL', KEYS[1], KEYS[2])
            return 1
            """, Long.class);

    private static final String NO_OWNER = "";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final String prefix;

    public RedisIdempotencyStore(StringRedisTemplate redis, ObjectMapper mapper, String prefix) {
        this.redis = redis;
        this.mapper = mapper;
        this.prefix = prefix;
    }

    @Override
    public ClaimResult claim(String key, String fingerprint, Duration ttl) {
        return claim(key, fingerprint, ttl, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>An ownerless claim clears any owner key left behind, so a claim that expired cannot pass
     * the owner check against the claim that replaced it.
     */
    @Override
    public ClaimResult claim(String key, String fingerprint, Duration ttl, String owner) {
        String k = prefix + key;
        List<String> keys = List.of(k, ownerKey(k));
        String ownerArg = owner == null ? NO_OWNER : owner;
        for (int attempt = 0; attempt < CLAIM_RETRY_ATTEMPTS; attempt++) {
            var candidate = IdempotencyRecord.inProgress(fingerprint, Instant.now());
            Long won = redis.execute(CLAIM, keys, write(candidate), millis(ttl), ownerArg);
            if (Long.valueOf(1).equals(won)) {
                return new ClaimResult.Acquired();
            }

            String raw = redis.opsForValue().get(k);
            if (raw != null) {
                return new ClaimResult.AlreadyHeld(read(raw));
            }
            // Expired between SET NX and GET - retry, bounded.
        }
        // Pathological churn: fail open, execute unprotected rather than error the request. No
        // owner was recorded, so if another request claims the key meanwhile, this run's response
        // is not stored over it.
        return new ClaimResult.Acquired();
    }

    @Override
    public void complete(String key, IdempotencyRecord record, Duration ttl) {
        String k = prefix + key;
        redis.execute(COMPLETE, List.of(k, ownerKey(k)), write(record), millis(ttl), NO_OWNER);
    }

    @Override
    public boolean complete(String key, String owner, IdempotencyRecord record, Duration ttl) {
        Objects.requireNonNull(owner, "owner");
        String k = prefix + key;
        Long done = redis.execute(COMPLETE, List.of(k, ownerKey(k)), write(record), millis(ttl), owner);
        return Long.valueOf(1).equals(done);
    }

    @Override
    public void release(String key) {
        String k = prefix + key;
        redis.delete(List.of(k, ownerKey(k)));
    }

    @Override
    public boolean release(String key, String owner) {
        Objects.requireNonNull(owner, "owner");
        String k = prefix + key;
        Long done = redis.execute(RELEASE, List.of(k, ownerKey(k)), owner);
        return Long.valueOf(1).equals(done);
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        return Optional.ofNullable(redis.opsForValue().get(prefix + key)).map(this::read);
    }

    /**
     * The owner key for a record key, in the record's cluster slot.
     *
     * <p>Redis Cluster hashes only the part of a key between the first {@code {}} and the next
     * {@code }}, when that part is non-empty. If the record key already has such a tag - from a
     * prefix like {@code {idem}:} - appending keeps it. Otherwise the whole record key becomes the
     * tag, which hashes to the same slot as the untagged record key. The one key that cannot be
     * matched this way contains a {@code }} without a tag before it; storage keys are hex digests,
     * so that can only come from an unusual {@code key-prefix}, and only matters under Cluster.
     */
    static String ownerKey(String recordKey) {
        int open = recordKey.indexOf('{');
        if (open >= 0) {
            int close = recordKey.indexOf('}', open + 1);
            if (close > open + 1) {
                return recordKey + ":owner";
            }
        }
        return "{" + recordKey + "}:owner";
    }

    private static String millis(Duration ttl) {
        return Long.toString(ttl.toMillis());
    }

    private String write(IdempotencyRecord record) {
        try {
            return mapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize IdempotencyRecord for Redis", e);
        }
    }

    private IdempotencyRecord read(String raw) {
        try {
            return mapper.readValue(raw, IdempotencyRecord.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize IdempotencyRecord from Redis", e);
        }
    }
}
