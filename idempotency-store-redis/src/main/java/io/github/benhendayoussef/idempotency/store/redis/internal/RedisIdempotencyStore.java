package io.github.benhendayoussef.idempotency.store.redis.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed store: fast, at-least-once (see the durability caveat in the README). The atomic
 * claim is a single {@code SETNX} round trip; no locks.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final int CLAIM_RETRY_ATTEMPTS = 3;

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
        String k = prefix + key;
        for (int attempt = 0; attempt < CLAIM_RETRY_ATTEMPTS; attempt++) {
            var candidate = IdempotencyRecord.inProgress(fingerprint, Instant.now());
            Boolean won = redis.opsForValue().setIfAbsent(k, write(candidate), ttl);
            if (Boolean.TRUE.equals(won)) {
                return new ClaimResult.Acquired();
            }

            String raw = redis.opsForValue().get(k);
            if (raw != null) {
                return new ClaimResult.AlreadyHeld(read(raw));
            }
            // Expired between SETNX and GET - retry, bounded.
        }
        // Pathological churn: fail open, execute unprotected rather than error the request.
        return new ClaimResult.Acquired();
    }

    @Override
    public void complete(String key, IdempotencyRecord record, Duration ttl) {
        redis.opsForValue().set(prefix + key, write(record), ttl);
    }

    @Override
    public void release(String key) {
        redis.delete(prefix + key);
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        return Optional.ofNullable(redis.opsForValue().get(prefix + key)).map(this::read);
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
