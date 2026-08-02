package io.github.benhendayoussef.idempotency.store.redis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord.State;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore.ClaimResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs against a real Redis via Testcontainers - a mock can't catch a non-atomic claim, and
 * that's the whole correctness argument for this store (see the README's §1.3).
 */
@Testcontainers
class RedisIdempotencyStoreTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private RedisIdempotencyStore store() {
        var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        var template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        var mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        return new RedisIdempotencyStore(template, mapper, "test:idempotency:");
    }

    @Test
    void concurrentClaimsForTheSameKeyLetExactlyOneCallerWin() throws Exception {
        RedisIdempotencyStore store = store();
        String key = "concurrent-key-" + System.nanoTime();
        int threads = 32;

        var start = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger acquiredCount = new AtomicInteger();
        List<ClaimResult> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ClaimResult result = store.claim(key, "fp", Duration.ofMinutes(5));
                results.add(result);
                if (result instanceof ClaimResult.Acquired) {
                    acquiredCount.incrementAndGet();
                }
            });
        }
        start.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(results).hasSize(threads);
        assertThat(acquiredCount.get()).isEqualTo(1);
        assertThat(results).filteredOn(r -> r instanceof ClaimResult.AlreadyHeld).hasSize(threads - 1);
    }

    @Test
    void completeThenFindReturnsTheStoredRecord() {
        RedisIdempotencyStore store = store();
        String key = "complete-key-" + System.nanoTime();
        store.claim(key, "fp", Duration.ofMinutes(5));

        var record = new IdempotencyRecord(State.COMPLETED, "fp", 201, "java.lang.String", "\"hello\"", Instant.now());
        store.complete(key, record, Duration.ofMinutes(5));

        assertThat(store.find(key)).isPresent().get().satisfies(found -> {
            assertThat(found.state()).isEqualTo(State.COMPLETED);
            assertThat(found.status()).isEqualTo(201);
            assertThat(found.payload()).isEqualTo("\"hello\"");
        });
    }

    @Test
    void releaseRemovesTheClaim() {
        RedisIdempotencyStore store = store();
        String key = "release-key-" + System.nanoTime();
        store.claim(key, "fp", Duration.ofMinutes(5));

        store.release(key);

        assertThat(store.find(key)).isEmpty();
        assertThat(store.claim(key, "fp", Duration.ofMinutes(5))).isInstanceOf(ClaimResult.Acquired.class);
    }
}
