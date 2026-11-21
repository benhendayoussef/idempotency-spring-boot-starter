package io.github.benhendayoussef.idempotency.store.jdbc.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord.State;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs against a real Postgres via Testcontainers. Covers the atomic claim (the same correctness
 * argument as the Redis test) and the exactly-once guarantee from the README's §1.4: the
 * completion write joins the caller's transaction and rolls back with it.
 */
@Testcontainers
class JdbcIdempotencyStoreTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static SimpleDriverDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUpSchema() {
        dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        jdbcTemplate = new JdbcTemplate(dataSource);
        // The shipped script rather than a copy of it, so the tests exercise the schema users get.
        new ResourceDatabasePopulator(new ClassPathResource("db/idempotency/postgres.sql")).execute(dataSource);

        // The shape a 1.1 install has: created before the owner column existed.
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS legacy_idempotency_record (
                    id            VARCHAR(64)  PRIMARY KEY,
                    state         VARCHAR(16)  NOT NULL,
                    fingerprint   VARCHAR(64)  NOT NULL,
                    status        INTEGER,
                    payload_type  VARCHAR(512),
                    payload       TEXT,
                    created_at    TIMESTAMPTZ  NOT NULL,
                    expires_at    TIMESTAMPTZ  NOT NULL
                )
                """);
    }

    private JdbcIdempotencyStore store() {
        return new JdbcIdempotencyStore(new NamedParameterJdbcTemplate(dataSource), "idempotency_record",
                new PostgresDialect());
    }

    /**
     * {@code @Transactional} only does anything through an AOP proxy. A plain {@link #store()}
     * still rides connection-binding (enough for REQUIRED to "just work"), but REQUIRES_NEW
     * needs the interceptor to actually suspend the outer transaction, so build a real proxy.
     */
    private IdempotencyStore transactionalStore(DataSourceTransactionManager txManager) {
        var interceptor = new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(store());
        factory.addAdvice(interceptor);
        return (IdempotencyStore) factory.getProxy();
    }

    @Test
    void concurrentClaimsForTheSameKeyLetExactlyOneCallerWin() throws Exception {
        JdbcIdempotencyStore store = store();
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
    void completeJoinsTheCallersTransactionAndRollsBackWithIt() {
        var txManager = new DataSourceTransactionManager(dataSource);
        IdempotencyStore store = transactionalStore(txManager);
        String key = "tx-key-" + System.nanoTime();
        store.claim(key, "fp", Duration.ofMinutes(5));

        var txTemplate = new TransactionTemplate(txManager);
        txTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                var record = new IdempotencyRecord(State.COMPLETED, "fp", 201,
                        "java.lang.String", "\"hello\"", Instant.now());
                store.complete(key, record, Duration.ofMinutes(5));
                status.setRollbackOnly();
            }
        });

        // claim() itself committed earlier (outside the transaction), so the row still exists -
        // but the completion write rolled back with the business transaction, leaving it
        // IN_PROGRESS, exactly as if the business logic itself had failed.
        assertThat(store.find(key)).isPresent().get()
                .extracting(IdempotencyRecord::state).isEqualTo(State.IN_PROGRESS);
    }

    /**
     * Clock-source check. Actually simulating clock skew between JVM instances isn't
     * practical against a stock Testcontainers Postgres image, so this instead proves the
     * mechanism directly: the persisted {@code expires_at} is anchored to Postgres's own
     * {@code clock_timestamp()} output at write time, not a {@code java.time.Instant} the JVM
     * computed and sent over - confirmed by comparing it against a {@code clock_timestamp()}
     * queried from the same connection immediately afterwards, rather than against
     * {@code Instant.now()}.
     */
    @Test
    void expiryIsAnchoredToTheDatabasesClockNotTheJvms() {
        JdbcIdempotencyStore store = store();
        String key = "clock-key-" + System.nanoTime();
        Duration ttl = Duration.ofSeconds(30);

        store.claim(key, "fp", ttl);

        java.sql.Timestamp dbNow = jdbcTemplate.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class);
        java.sql.Timestamp expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM idempotency_record WHERE id = ?", java.sql.Timestamp.class, key);

        assertThat(expiresAt.toInstant())
                .as("expires_at must be ~ttl after the database's own clock, not the JVM's")
                .isCloseTo(dbNow.toInstant().plus(ttl), org.assertj.core.api.Assertions.within(2, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void releaseRunsInItsOwnTransactionEvenWhenCalledFromARollingBackOne() {
        var txManager = new DataSourceTransactionManager(dataSource);
        IdempotencyStore store = transactionalStore(txManager);
        String key = "release-tx-key-" + System.nanoTime();
        store.claim(key, "fp", Duration.ofMinutes(5));

        var txTemplate = new TransactionTemplate(txManager);
        txTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                status.setRollbackOnly();
                store.release(key); // REQUIRES_NEW: must commit despite the outer rollback
            }
        });

        assertThat(store.find(key)).isEmpty();
        assertThat(store.claim(key, "fp", Duration.ofMinutes(5))).isInstanceOf(ClaimResult.Acquired.class);
    }

    // --- Claim ownership (issue #19) ---------------------------------------------------------

    private static final Duration LONG = Duration.ofMinutes(5);

    private static IdempotencyRecord completed(String payload) {
        return new IdempotencyRecord(State.COMPLETED, "fp", 201, "java.lang.String", payload, Instant.now());
    }

    private static String ownerOf(String key) {
        return jdbcTemplate.queryForObject("SELECT owner FROM idempotency_record WHERE id = ?", String.class, key);
    }

    @Test
    void theShippedScriptAddsTheOwnerColumnToATableThatPredatesIt() {
        jdbcTemplate.execute("CREATE TABLE script_upgrade_probe (LIKE legacy_idempotency_record INCLUDING ALL)");
        try {
            String upgraded = new ClassPathResource("db/idempotency/postgres.sql").getContentAsString(
                    java.nio.charset.StandardCharsets.UTF_8).replace("idempotency_record", "script_upgrade_probe")
                    .replace("ix_idempotency_expires", "ix_script_upgrade_probe");
            new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(
                    upgraded.getBytes(java.nio.charset.StandardCharsets.UTF_8))).execute(dataSource);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_name = 'script_upgrade_probe' AND column_name = 'owner'
                    """, Integer.class)).isEqualTo(1);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } finally {
            jdbcTemplate.execute("DROP TABLE script_upgrade_probe");
        }
    }

    @Test
    void theClaimRecordsItsOwnerAndTheOwnerCompletesIt() {
        JdbcIdempotencyStore store = store();
        String key = "own-" + System.nanoTime();

        store.claim(key, "fp", LONG, "a");
        assertThat(ownerOf(key)).isEqualTo("a");

        assertThat(store.complete(key, "a", completed("\"a\""), LONG)).isTrue();
        assertThat(store.find(key)).get().extracting(IdempotencyRecord::payload).isEqualTo("\"a\"");
    }

    @Test
    void anotherOwnersLiveClaimBlocksCompletionAndRelease() throws Exception {
        JdbcIdempotencyStore store = store();
        String key = "other-" + System.nanoTime();
        store.claim(key, "fp", Duration.ofMillis(50), "a");
        Thread.sleep(150);
        assertThat(store.claim(key, "fp", LONG, "b")).isInstanceOf(ClaimResult.Acquired.class);
        assertThat(ownerOf(key)).as("a reclaim must take the owner over").isEqualTo("b");

        assertThat(store.complete(key, "a", completed("\"a\""), LONG)).isFalse();
        assertThat(store.release(key, "a")).isFalse();
        assertThat(store.find(key)).get().extracting(IdempotencyRecord::state).isEqualTo(State.IN_PROGRESS);

        assertThat(store.complete(key, "b", completed("\"b\""), LONG)).isTrue();
        assertThat(store.complete(key, "a", completed("\"a\""), LONG))
                .as("the winner's stored response must not be overwritten either")
                .isFalse();
        assertThat(store.find(key)).get().extracting(IdempotencyRecord::payload).isEqualTo("\"b\"");
    }

    @Test
    void theOwnerReleasesItsOwnClaimAndAGoneKeyCountsAsReleased() {
        JdbcIdempotencyStore store = store();
        String key = "release-own-" + System.nanoTime();
        store.claim(key, "fp", LONG, "a");

        assertThat(store.release(key, "a")).isTrue();
        assertThat(store.find(key)).isEmpty();
        assertThat(store.release(key, "a")).isTrue();
    }

    /** What 1.1 did, and must keep doing: a slow run nobody competed with still stores its response. */
    @Test
    void anExpiredClaimNobodyReclaimedIsStillCompleted() throws Exception {
        JdbcIdempotencyStore store = store();
        String key = "expired-" + System.nanoTime();
        store.claim(key, "fp", Duration.ofMillis(50), "a");
        Thread.sleep(150);

        assertThat(store.complete(key, "a", completed("\"a\""), LONG)).isTrue();
        assertThat(store.find(key)).get().extracting(IdempotencyRecord::payload).isEqualTo("\"a\"");
    }

    /** A row written by a 1.1 instance during a rolling upgrade has no owner and is anyone's to settle. */
    @Test
    void anUnownedRowCanBeSettledByAnyOwner() {
        JdbcIdempotencyStore store = store();
        String key = "unowned-" + System.nanoTime();
        store.claim(key, "fp", LONG);
        assertThat(ownerOf(key)).isNull();

        assertThat(store.complete(key, "a", completed("\"a\""), LONG)).isTrue();
    }

    @Test
    void withoutTheOwnerColumnTheStoreBehavesAsIn11() {
        var store = new JdbcIdempotencyStore(new NamedParameterJdbcTemplate(dataSource),
                "legacy_idempotency_record", new PostgresDialect());
        String key = "legacy-" + System.nanoTime();

        assertThat(store.claim(key, "fp", LONG, "a")).isInstanceOf(ClaimResult.Acquired.class);
        assertThat(store.complete(key, "b", completed("\"b\""), LONG))
                .as("no column to check against, so completion is unconditional, as before")
                .isTrue();
        assertThat(store.find(key)).get().extracting(IdempotencyRecord::payload).isEqualTo("\"b\"");
        assertThat(store.release(key, "c")).isTrue();
        assertThat(store.find(key)).isEmpty();
    }
}
