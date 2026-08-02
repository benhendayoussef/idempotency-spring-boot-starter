package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.benhendayoussef.idempotency.api.Idempotent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code on-conflict=WAIT} (the default) polls on the servlet request thread for up to
 * {@code wait-timeout}. The MockMvc-based conflict tests prove that works correctly for a handful
 * of requests, but MockMvc never exercises the connector's own thread pool at all. This test uses a
 * real embedded server (same {@code RANDOM_PORT} + plain {@link HttpClient} technique as
 * {@link VirtualThreadsTest}, for the same reason - MockMvc dispatches in-process on the calling
 * thread) with a deliberately small Tomcat thread pool, so the exposure is observable without
 * needing hundreds of concurrent duplicates.
 *
 * <p>This is an <strong>investigative</strong> test rather than a correctness gate: it reports what
 * it observes and only fails if a request never settles at all. Whether the measured degradation
 * warrants changing the default {@code on-conflict} policy is a judgement call, deliberately not
 * encoded as an assertion here. See the README's limitations section.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = WaitPolicyLoadTest.TestApp.class,
        properties = {
                "idempotency.store=memory",
                "idempotency.wait-timeout=3s",
                // Deliberately small so a handful of concurrent WAIT-policy duplicates - not
                // hundreds - is enough to exhaust it and make the exposure observable in a test
                // that still runs in a few seconds.
                "server.tomcat.threads.max=8",
                "server.tomcat.threads.min-spare=8",
                "server.tomcat.accept-count=4",
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE_AND_SECURITY
        })
class WaitPolicyLoadTest {

    @LocalServerPort
    private int port;

    /**
     * Fires 20 concurrent WAIT-policy duplicates (more than the 8-thread pool) at an endpoint whose
     * first call is held open, and polls a completely unrelated, unprotected endpoint at the same
     * time to check whether the pile-up starves the pool for other traffic too - not just the
     * idempotent endpoint itself.
     */
    @Test
    void manyConcurrentWaitDuplicates_reportThreadPoolBehaviorUnderLoad() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String key = "wait-load-" + System.nanoTime();

        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        SlowController.handlerEnteredLatch = handlerEntered;
        SlowController.releaseLatch = releaseHandler;

        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            // First call: claims the key and blocks in the handler until we release it below.
            Future<HttpResponse<String>> firstCall = pool.submit(() -> client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/m/slow"))
                            .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString()));

            assertThat(handlerEntered.await(10, TimeUnit.SECONDS))
                    .as("first call must have claimed the key and entered the handler").isTrue();

            // 20 duplicates, same key, all WAIT-policy: each occupies a servlet thread while it
            // polls, for up to wait-timeout (3s) or until the first call completes.
            int duplicateCount = 20;
            List<Future<CallOutcome>> duplicates = new java.util.ArrayList<>();
            for (int i = 0; i < duplicateCount; i++) {
                duplicates.add(pool.submit(() -> timedCall(client,
                        "http://localhost:" + port + "/m/slow", key)));
            }

            // Concurrently: hammer a completely unrelated, unprotected endpoint while the
            // duplicates are piled up, to see whether the pool exhaustion starves other traffic.
            List<CallOutcome> healthChecks = new CopyOnWriteArrayList<>();
            Future<?> healthPoller = pool.submit(() -> {
                long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (System.nanoTime() < deadline) {
                    healthChecks.add(timedCall(client, "http://localhost:" + port + "/m/health", null));
                }
            });

            // Hold the first call open for a second so the duplicates genuinely pile up before
            // anything can resolve, then let it complete.
            Thread.sleep(1000);
            releaseHandler.countDown();

            HttpResponse<String> first = firstCall.get(15, TimeUnit.SECONDS);
            assertThat(first.statusCode()).isEqualTo(200);

            healthPoller.get(15, TimeUnit.SECONDS);

            int completed = 0;
            int failed = 0;
            long maxDuplicateMillis = 0;
            for (Future<CallOutcome> f : duplicates) {
                CallOutcome outcome = f.get(15, TimeUnit.SECONDS);
                if (outcome.succeeded) {
                    completed++;
                    maxDuplicateMillis = Math.max(maxDuplicateMillis, outcome.millis);
                } else {
                    failed++;
                }
            }

            long maxHealthMillis = healthChecks.stream()
                    .filter(o -> o.succeeded).mapToLong(o -> o.millis).max().orElse(-1);
            long healthFailures = healthChecks.stream().filter(o -> !o.succeeded).count();

            // Report, don't gate: the point is to observe and document the behaviour, not to
            // assert that a specific outcome is acceptable - that judgement (change the default?
            // document a concurrency ceiling?) belongs to whoever reads these numbers.
            System.out.printf(
                    "%nWAIT-under-load result: %d/%d duplicates completed (max %dms), %d failed/timed out; "
                            + "health endpoint: %d checks, max %dms, %d failed/timed out during the pile-up.%n",
                    completed, duplicateCount, maxDuplicateMillis, failed,
                    healthChecks.size(), maxHealthMillis, healthFailures);

            // Every duplicate must eventually settle one way or another (either a real HTTP
            // response - replay or 409 - or a client-side failure) within the bounded 15s wait
            // above; a hang past that would itself be the finding. Beyond that, no pass/fail
            // judgement is made here.
            assertThat(completed + failed).isEqualTo(duplicateCount);
        } finally {
            pool.shutdownNow();
        }
    }

    private static CallOutcome timedCall(HttpClient client, String url, String key) {
        long start = System.nanoTime();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
            if (key != null) {
                builder.header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.noBody());
            } else {
                builder.GET();
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long millis = (System.nanoTime() - start) / 1_000_000;
            boolean ok = response.statusCode() == 200 || response.statusCode() == 201 || response.statusCode() == 409;
            return new CallOutcome(ok, millis);
        } catch (Exception e) {
            long millis = (System.nanoTime() - start) / 1_000_000;
            return new CallOutcome(false, millis);
        }
    }

    private record CallOutcome(boolean succeeded, long millis) {
    }

    // SlowController is NOT nested inside TestApp: Spring's ConfigurationClassParser
    // auto-registers @Component-stereotyped member classes of any @Configuration class
    // regardless of @ComponentScan, which would produce a duplicate bean and an "ambiguous
    // mapping" failure alongside the explicit @Bean method below (see RedisStoreUnavailableBehaviorTest
    // for the same pitfall documented in more detail).
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        SlowController slowController() {
            return new SlowController();
        }
    }

    @RestController
    static class SlowController {
        static volatile CountDownLatch handlerEnteredLatch;
        static volatile CountDownLatch releaseLatch;
        final AtomicInteger count = new AtomicInteger();

        @Idempotent
        @PostMapping("/m/slow")
        ResponseEntity<String> slow() throws InterruptedException {
            count.incrementAndGet();
            handlerEnteredLatch.countDown();
            releaseLatch.await(10, TimeUnit.SECONDS);
            return ResponseEntity.ok("done");
        }

        @GetMapping("/m/health")
        ResponseEntity<String> health() {
            return ResponseEntity.ok("ok");
        }
    }
}
