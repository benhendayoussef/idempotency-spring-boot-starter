package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.benhendayoussef.idempotency.api.Idempotent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

/**
 * Measures what {@code idempotency.jdbc.join-transaction} costs under connection-pool pressure.
 *
 * <p>The README states that joined mode pulls a handler with no {@code @Transactional} of its own
 * into a transaction, so it holds a database connection for its <em>whole</em> duration rather than
 * not holding one at all. That claim was documented without a number attached. This measures it.
 *
 * <p>The workload is deliberately the worst case for the property and the best case for the default:
 * a handler that does <strong>no database work</strong> and is slow for some other reason - an
 * external API call, an image resize. In the default mode it needs no connection at all while it
 * runs; in joined mode it occupies one throughout. Everything else is held constant, so the
 * difference between the two subclasses is attributable to the property and nothing else.
 *
 * <p>The pool is deliberately smaller than the concurrency (4 connections, 16 concurrent requests)
 * so the ceiling is reachable in a test that finishes in seconds. Real deployments should size the
 * pool for concurrent in-flight requests - which is exactly the guidance these numbers support.
 *
 * <p><strong>Investigative, not a gate.</strong> Like {@link WaitPolicyLoadTest}, it reports what it
 * observes and only fails if a request never settles at all. Whether the measured cost is acceptable
 * is a judgement for whoever reads the numbers, not an assertion encoded here.
 */
abstract class AbstractTxJoinLoadTest {

    /**
     * Static and shared: both subclasses run against one container. Testcontainers starts it on
     * first use and reuses it for the second context rather than paying startup twice.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 16 concurrent requests against a 4-connection pool: four times the ceiling. */
    private static final int CONCURRENCY = 16;

    /** Long enough that connection hold time dominates scheduling noise, short enough to stay fast. */
    private static final int HANDLER_MILLIS = 250;

    @LocalServerPort
    private int port;

    /** Label for the printed report - "joined" or "default". */
    abstract String modeLabel();

    @Test
    void nonTransactionalSlowHandler_reportConnectionPoolBehaviorUnderLoad() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        List<CallOutcome> outcomes = new CopyOnWriteArrayList<>();

        try {
            // Warm up first, and discard it. Without this the measurement is dominated by one-off
            // costs that have nothing to do with the property: JIT, Tomcat's first-request
            // initialisation, and Hikari opening its connections lazily against a Dockerised
            // Postgres. The first run of this test showed the default mode at 5x its theoretical
            // floor purely from those, which would have made the comparison meaningless.
            warmUp(client, pool);

            List<Future<?>> calls = new java.util.ArrayList<>();
            long start = System.nanoTime();
            for (int i = 0; i < CONCURRENCY; i++) {
                // Distinct keys: every request executes. Duplicates of one key would be answered by
                // the replay path, which never opens a transaction and so would measure nothing.
                String key = "txjoin-load-" + System.nanoTime() + "-" + i;
                calls.add(pool.submit(() -> outcomes.add(timedCall(client,
                        "http://localhost:" + port + "/m/slow-io", key))));
            }
            for (Future<?> f : calls) {
                f.get(60, TimeUnit.SECONDS);
            }
            long wallMillis = (System.nanoTime() - start) / 1_000_000;

            long succeeded = outcomes.stream().filter(CallOutcome::succeeded).count();
            long failed = outcomes.size() - succeeded;
            long maxMillis = outcomes.stream().filter(CallOutcome::succeeded)
                    .mapToLong(CallOutcome::millis).max().orElse(-1);
            long medianMillis = median(outcomes);

            // The floor: with enough connections these run fully in parallel, so the whole batch
            // should take not much more than one handler duration. How far above that the real
            // number lands is the cost of the mode.
            System.out.printf("%n[tx-join load / %s] %d concurrent non-transactional %dms handlers, "
                            + "pool of 4: %d ok / %d failed, median %dms, max %dms, wall %dms "
                            + "(unconstrained floor ~%dms)%n",
                    modeLabel(), CONCURRENCY, HANDLER_MILLIS, succeeded, failed,
                    medianMillis, maxMillis, wallMillis, HANDLER_MILLIS);

            // The only real gate: every request settled one way or another inside the bounded wait
            // above. A hang past that would itself be the finding.
            assertThat(outcomes).hasSize(CONCURRENCY);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Two full rounds at the measured concurrency, discarded, so the pool and JIT are hot. */
    private void warmUp(HttpClient client, ExecutorService pool) throws Exception {
        for (int round = 0; round < 2; round++) {
            List<Future<?>> warm = new java.util.ArrayList<>();
            for (int i = 0; i < CONCURRENCY; i++) {
                String key = "txjoin-warmup-" + System.nanoTime() + "-" + i;
                warm.add(pool.submit(() -> timedCall(client, "http://localhost:" + port + "/m/slow-io", key)));
            }
            for (Future<?> f : warm) {
                f.get(60, TimeUnit.SECONDS);
            }
        }
    }

    private static long median(List<CallOutcome> outcomes) {
        long[] sorted = outcomes.stream().filter(CallOutcome::succeeded)
                .mapToLong(CallOutcome::millis).sorted().toArray();
        return sorted.length == 0 ? -1 : sorted[sorted.length / 2];
    }

    private static CallOutcome timedCall(HttpClient client, String url, String key) {
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("Idempotency-Key", key)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            long millis = (System.nanoTime() - start) / 1_000_000;
            return new CallOutcome(response.statusCode() == 201, millis);
        } catch (Exception e) {
            return new CallOutcome(false, (System.nanoTime() - start) / 1_000_000);
        }
    }

    private record CallOutcome(boolean succeeded, long millis) {
    }

    // Not nested inside TestApp - Spring auto-registers @Component-stereotyped member classes of a
    // @Configuration class regardless of @ComponentScan, which would collide with the @Bean method
    // below. Same pitfall WaitPolicyLoadTest documents.
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        SlowIoController slowIoController() {
            return new SlowIoController();
        }
    }

    @RestController
    static class SlowIoController {
        final AtomicInteger count = new AtomicInteger();

        /**
         * No {@code @Transactional} and no database access: it stands in for a handler that is slow
         * for reasons of its own. Under the default mode it needs no connection while it runs; under
         * joined mode the aspect's transaction holds one for the entire sleep.
         */
        @Idempotent
        @PostMapping("/m/slow-io")
        ResponseEntity<String> slowIo() throws InterruptedException {
            count.incrementAndGet();
            Thread.sleep(HANDLER_MILLIS);
            return ResponseEntity.status(201).body("done");
        }
    }
}
