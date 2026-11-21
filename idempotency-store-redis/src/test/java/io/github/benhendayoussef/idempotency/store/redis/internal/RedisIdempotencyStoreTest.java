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
import io.lettuce.core.cluster.SlotHash;
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
        var template = template();
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

    // Claim ownership (issue #19). The same rules InMemoryIdempotencyStoreOwnerTest pins, here
    // against the Lua scripts, plus what is specific to keeping the owner in a key of its own.

    private static final Duration LONG = Duration.ofMinutes(5);

    private static IdempotencyRecord completed(String payload) {
        return new IdempotencyRecord(State.COMPLETED, "fp", 201, "java.lang.String", payload, Instant.now());
    }

    private static String uniqueKey(String name) {
        return name + "-" + System.nanoTime();
    }

    @Test
    void theOwnerCompletesItsOwnClaim() {
        RedisIdempotencyStore store = store();
        String key = uniqueKey("owner-complete");
        store.claim(key, "fp", LONG, "a");

        assertThat(store.complete(key, "a", completed("\"a\""), LONG)).isTrue();
        assertThat(store.find(key)).get().extracting(IdempotencyRecord::payload).isEqualTo("\"a\"");
    }

    @Test
    void anotherOwnersLiveClaimBlocksCompletion() throws Exception {
        RedisIdempotencyStore store = store();
        String key = uniqueKey("owner-blocks-complete");
        store.claim(key, "fp", Duration.ofMillis(50), "a");
        Thread.sleep(100);
        assertThat(store.claim(key, "fp", LONG, "b")).isInstanceOf(ClaimResult.Acquired.class);
        assertThat(store.complete(key, "b", completed("\"b\""), LONG)).isTrue();

        assertThat(store.complete(key, "a", completed("\"a\""), LONG))
                .as("a run whose claim was reclaimed must not overwrite the winner's response")
                .isFalse();
        assertThat(store.find(key)).get().extracting(IdempotencyRecord::payload).isEqualTo("\"b\"");
    }

    @Test
    void anotherOwnersLiveClaimBlocksRelease() throws Exception {
        RedisIdempotencyStore store = store();
        String key = uniqueKey("owner-blocks-release");
        store.claim(key, "fp", Duration.ofMillis(50), "a");
        Thread.sleep(100);
        store.claim(key, "fp", LONG, "b");

        assertThat(store.release(key, "a"))
                .as("a run whose claim was reclaimed must not delete the winner's claim")
                .isFalse();
        assertThat(store.claim(key, "fp", LONG, "c")).isInstanceOf(ClaimResult.AlreadyHeld.class);
    }

    @Test
    void theOwnerReleasesItsOwnClaim() {
        RedisIdempotencyStore store = store();
        String key = uniqueKey("owner-release");
        store.claim(key, "fp", LONG, "a");

        assertThat(store.release(key, "a")).isTrue();
        assertThat(store.claim(key, "fp", LONG, "b")).isInstanceOf(ClaimResult.Acquired.class);
    }

    /** What 1.1 did, and must keep doing: a slow run nobody competed with still stores its response. */
    @Test
    void anExpiredClaimNobodyReclaimedIsStillCompleted() throws Exception {
        RedisIdempotencyStore store = store();
        String key = uniqueKey("owner-expired");
        store.claim(key, "fp", Duration.ofMillis(50), "a");
        Thread.sleep(100);

        assertThat(store.complete(key, "a", completed("\"a\""), LONG)).isTrue();
        assertThat(store.find(key)).get().extracting(IdempotencyRecord::payload).isEqualTo("\"a\"");
    }

    @Test
    void anUnownedClaimCanBeSettledByAnyOwner() {
        RedisIdempotencyStore store = store();
        String completeKey = uniqueKey("unowned-complete");
        store.claim(completeKey, "fp", LONG);
        assertThat(store.complete(completeKey, "a", completed("\"a\""), LONG)).isTrue();

        String releaseKey = uniqueKey("unowned-release");
        store.claim(releaseKey, "fp", LONG);
        assertThat(store.release(releaseKey, "a")).isTrue();
        assertThat(store.find(releaseKey)).isEmpty();
    }

    @Test
    void releasingAKeyThatIsAlreadyGoneSucceeds() {
        assertThat(store().release(uniqueKey("absent"), "a")).isTrue();
    }

    /** A 1.1 instance mid rolling upgrade writes the bare record and nothing else. */
    @Test
    void aRecordWrittenWithoutAnOwnerKeyIsUnowned() throws Exception {
        RedisIdempotencyStore store = store();
        String key = uniqueKey("legacy-record");
        var mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        template().opsForValue().set("test:idempotency:" + key,
                mapper.writeValueAsString(IdempotencyRecord.inProgress("fp", Instant.now())), LONG);

        assertThat(store.complete(key, "a", completed("\"a\""), LONG)).isTrue();
    }

    /** A 1.1 instance releasing a 1.2 claim deletes the record but leaves its owner key behind. */
    @Test
    void anOwnerKeyWhoseRecordIsGoneBlocksNothing() {
        RedisIdempotencyStore store = store();
        String key = uniqueKey("orphan-owner");
        store.claim(key, "fp", LONG, "a");
        template().delete("test:idempotency:" + key);

        assertThat(store.complete(key, "b", completed("\"b\""), LONG)).isTrue();
    }

    @Test
    void anOwnerlessClaimClearsTheOwnerItReplaces() throws Exception {
        RedisIdempotencyStore store = store();
        String key = uniqueKey("ownerless-reclaim");
        store.claim(key, "fp", Duration.ofMillis(50), "a");
        Thread.sleep(100);
        store.claim(key, "fp", LONG);

        assertThat(template().hasKey(RedisIdempotencyStore.ownerKey("test:idempotency:" + key))).isFalse();
    }

    /** The stored record format is API: the owner must not leak into it. */
    @Test
    void theRecordItselfCarriesNoOwner() {
        RedisIdempotencyStore store = store();
        String key = uniqueKey("record-format");
        store.claim(key, "fp", LONG, "a");
        store.complete(key, "a", completed("\"a\""), LONG);

        assertThat(template().opsForValue().get("test:idempotency:" + key)).doesNotContain("owner");
    }

    @Test
    void theOwnerKeyIsInTheRecordsClusterSlot() {
        for (String recordKey : List.of("idempotency:" + "ab12".repeat(16), "{idem}:ab12", "x{y", "{")) {
            String ownerKey = RedisIdempotencyStore.ownerKey(recordKey);
            assertThat(SlotHash.getSlot(ownerKey)).as(ownerKey).isEqualTo(SlotHash.getSlot(recordKey));
        }
    }

    private StringRedisTemplate template() {
        var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        var template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }
}
