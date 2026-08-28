package io.github.benhendayoussef.idempotency.store.caffeine.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore.ClaimResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The semantics have to match the other stores exactly - the aspect cannot tell them apart - so the
 * interesting tests here are the two things this store adds over the built-in in-memory one:
 * entries are actually evicted, and memory has a ceiling.
 */
class CaffeineIdempotencyStoreTest {

    private final CaffeineIdempotencyStore store = new CaffeineIdempotencyStore(10_000);

    @Test
    void aFreshKeyIsAcquiredAndASecondClaimConflicts() {
        assertThat(store.claim("k", "fp", Duration.ofMinutes(5)))
                .isInstanceOf(ClaimResult.Acquired.class);
        assertThat(store.claim("k", "fp", Duration.ofMinutes(5)))
                .isInstanceOf(ClaimResult.AlreadyHeld.class);
    }

    @Test
    void completeThenFindReturnsTheStoredRecord() {
        store.claim("k", "fp", Duration.ofMinutes(5));
        var record = new IdempotencyRecord(IdempotencyRecord.State.COMPLETED, "fp", 201,
                "java.lang.String", "\"body\"", Instant.now());
        store.complete("k", record, Duration.ofMinutes(5));

        assertThat(store.find("k")).isPresent().get()
                .extracting(IdempotencyRecord::payload).isEqualTo("\"body\"");
    }

    @Test
    void releaseMakesTheKeyImmediatelyReclaimable() {
        store.claim("k", "fp", Duration.ofMinutes(5));
        store.release("k");

        assertThat(store.find("k")).isEmpty();
        assertThat(store.claim("k", "fp", Duration.ofMinutes(5)))
                .isInstanceOf(ClaimResult.Acquired.class);
    }

    /**
     * The reason this store exists. The built-in in-memory store treats an expired entry as absent
     * but never removes it, so its map grows by one entry per key ever seen. Here the entry has to
     * actually leave the cache.
     */
    @Test
    void expiredEntriesAreEvictedRatherThanJustHidden() throws Exception {
        for (int i = 0; i < 50; i++) {
            store.claim("short-" + i, "fp", Duration.ofMillis(100));
        }
        assertThat(store.estimatedSize()).isEqualTo(50);

        // Well past the TTL, and deliberately so. Caffeine schedules variable expiry on a timer
        // wheel whose finest bucket spans roughly a second, so eviction is not immediate at the
        // moment a TTL passes - it happens on the next maintenance cycle after the wheel advances.
        // A shorter wait here fails intermittently and would look like a bug in the store.
        //
        // That lag is exactly why claim() and find() still compare expiry themselves rather than
        // trusting presence in the cache: an entry can outlive its TTL briefly, and must not be
        // treated as live during that window.
        Thread.sleep(2_500);

        assertThat(store.estimatedSize())
                .as("entries past their TTL must be reclaimed, not merely reported as absent")
                .isZero();
    }

    @Test
    void anExpiredKeyIsReclaimableByTheNextClaim() throws Exception {
        store.claim("k", "fp", Duration.ofMillis(50));
        Thread.sleep(200);

        assertThat(store.find("k")).isEmpty();
        assertThat(store.claim("k", "fp", Duration.ofMinutes(5)))
                .isInstanceOf(ClaimResult.Acquired.class);
    }

    /**
     * Reading must not extend a record's life. An idempotency window is absolute - a key is valid
     * for its TTL from when it was claimed - so a heavily replayed key must still expire on time.
     * Caffeine's default read policy would renew it, which is why the expiry explicitly returns the
     * remaining duration on read.
     */
    @Test
    void repeatedReadsDoNotExtendTheTtl() throws Exception {
        store.claim("k", "fp", Duration.ofMillis(300));
        for (int i = 0; i < 20; i++) {
            store.find("k");
            Thread.sleep(20);
        }
        assertThat(store.find("k"))
                .as("the key was read constantly but its window still ended on schedule")
                .isEmpty();
    }

    @Test
    void memoryIsBoundedWhenNewKeysOutrunExpiry() {
        var bounded = new CaffeineIdempotencyStore(100);
        for (int i = 0; i < 5_000; i++) {
            bounded.claim("k-" + i, "fp", Duration.ofHours(1));
        }
        assertThat(bounded.estimatedSize())
                .as("the ceiling is what keeps a long-running single instance from growing without limit")
                .isLessThanOrEqualTo(100);
    }

    /** Two duplicates racing the same fresh key: exactly one may be told it acquired it. */
    @Test
    void concurrentClaimsOnTheSameKeyProduceExactlyOneWinner() throws Exception {
        int threads = 32;
        var go = new CountDownLatch(1);
        var acquired = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    if (store.claim("race", "fp", Duration.ofMinutes(5)) instanceof ClaimResult.Acquired) {
                        acquired.incrementAndGet();
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(acquired.get()).isEqualTo(1);
    }
}
