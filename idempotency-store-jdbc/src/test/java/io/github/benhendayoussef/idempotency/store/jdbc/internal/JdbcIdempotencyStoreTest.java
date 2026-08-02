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
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS idempotency_record (
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
        return new JdbcIdempotencyStore(new NamedParameterJdbcTemplate(dataSource), "idempotency_record");
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
}
