package io.github.benhendayoussef.idempotency.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.benhendayoussef.idempotency.api.ConflictPolicy;
import io.github.benhendayoussef.idempotency.api.Idempotent;
import io.github.benhendayoussef.idempotency.behavior.TestAutoconfigExcludes;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import reactor.core.scheduler.Schedulers;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * WebFlux behaviour through a real reactive stack, driven with {@link WebTestClient}.
 *
 * <p>These mirror the servlet matrix cases that genuinely differ here: the request is read from the
 * Reactor context rather than a ThreadLocal, the store runs off the event loop, and the outcome is
 * only known when the returned {@code Mono} completes. Every one of them could pass on the servlet
 * side and fail on this one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = WebFluxIdempotencyBehaviorTest.ReactiveApp.class,
        properties = {"idempotency.store=memory", "idempotency.wait-timeout=3s",
                // spring-boot-starter-web is on this module's test classpath for the servlet tests,
                // and Boot prefers SERVLET whenever both stacks are present - so the web type has to
                // be forced, or this would silently exercise the servlet aspect instead.
                "spring.main.web-application-type=reactive",
                // Likewise spring-boot-starter-jdbc: without this Boot tries to build a DataSource
                // that this test never configures. Via the shared constant, which names both Boot
                // generations - hardcoding the Boot 4 class here matched nothing on Boot 3, so every
                // test in this class failed on a DataSource that should never have been created.
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE})
class WebFluxIdempotencyBehaviorTest {

    @Autowired
    private ReactiveController controller;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private IdempotencyProperties props;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        controller.reset();
        client = WebTestClient.bindToApplicationContext(context).build()
                .mutate().responseTimeout(Duration.ofSeconds(20)).build();
    }

    private WebTestClient.RequestHeadersSpec<?> post(String path, String key, String body) {
        return client.post().uri(path)
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .bodyValue(body);
    }

    @Test
    void firstCallExecutesAndTheDuplicateReplaysWithoutReExecuting() {
        String key = "wf-" + System.nanoTime();

        post("/r/echo", key, "{\"a\":1}").exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.a").isEqualTo(1);

        post("/r/echo", key, "{\"a\":1}").exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.a").isEqualTo(1);

        assertThat(controller.echoCount()).as("the duplicate must be replayed, not re-executed").isEqualTo(1);
    }

    /**
     * The case the operator chain has to lift explicitly. An empty {@code Mono} is a real response,
     * and without turning that emptiness into a value the completion step never runs - leaving the
     * key IN_PROGRESS until its TTL, so every later duplicate blocks or conflicts against a request
     * that actually succeeded.
     */
    @Test
    void aHandlerReturningAnEmptyMonoStillCompletesTheKey() {
        String key = "wf-empty-" + System.nanoTime();

        post("/r/empty", key, "{}").exchange().expectStatus().isOk();
        post("/r/empty", key, "{}").exchange().expectStatus().isOk();

        assertThat(controller.emptyCount())
                .as("an empty response must be recorded as completed so the duplicate replays")
                .isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentBodyIsRejectedRatherThanReplayed() {
        String key = "wf-fp-" + System.nanoTime();

        post("/r/echo", key, "{\"a\":1}").exchange().expectStatus().isCreated();
        post("/r/echo", key, "{\"a\":999}").exchange()
                // 422 by value: the enum constant was renamed to UNPROCESSABLE_CONTENT, and
                // asserting on the constant makes this fail on one Spring version and pass on another.
                .expectStatus().isEqualTo(422);

        assertThat(controller.echoCount()).isEqualTo(1);
    }

    @Test
    void a5xxReleasesTheKeySoARetryCanSucceed() {
        String key = "wf-5xx-" + System.nanoTime();
        controller.failNext();

        post("/r/flaky", key, "{}").exchange().expectStatus().is5xxServerError();
        post("/r/flaky", key, "{}").exchange().expectStatus().isCreated();

        assertThat(controller.flakyCount())
                .as("a transient failure releases the key, so the retry re-executes")
                .isEqualTo(2);
    }

    @Test
    void a4xxIsKeptAndReplayedToTheRetry() {
        String key = "wf-4xx-" + System.nanoTime();

        post("/r/bad", key, "{}").exchange().expectStatus().isBadRequest();
        // The replay must be a 400 too. It arrives as an ErrorResponse rather than a returned
        // ResponseEntity - see ReactiveIdempotencyAspect.replay - which is what keeps the status
        // intact regardless of what the handler declares it returns.
        post("/r/bad", key, "{}").exchange().expectStatus().isBadRequest();

        assertThat(controller.badCount())
                .as("a deterministic client error is replayed, not re-executed")
                .isEqualTo(1);
    }

    @Test
    void aRequestWithNoKeyPassesThroughAndExecutesEveryTime() {
        client.post().uri("/r/echo").header("Content-Type", "application/json")
                .bodyValue("{\"a\":1}").exchange().expectStatus().isCreated();
        client.post().uri("/r/echo").header("Content-Type", "application/json")
                .bodyValue("{\"a\":1}").exchange().expectStatus().isCreated();

        assertThat(controller.echoCount()).isEqualTo(2);
    }

    @Test
    void aSlowHandlerStillReplaysItsResponseToALaterDuplicate() {
        String key = "wf-slow-" + System.nanoTime();

        post("/r/slow", key, "{}").exchange().expectStatus().isCreated();
        post("/r/slow", key, "{}").exchange().expectStatus().isCreated();

        assertThat(controller.slowCount()).isEqualTo(1);
    }

    /**
     * Two duplicates genuinely in flight at once. The loser takes the WAIT path, which on this stack
     * holds no thread while it waits - the delay is a timer, not a sleep. That removes the
     * thread-pool exhaustion the servlet side documents as a limitation.
     */
    @Test
    void twoConcurrentDuplicatesExecuteOnceAndBothGetTheSameResponse() {
        String key = "wf-race-" + System.nanoTime();

        Mono<Integer> a = sendForStatus("/r/slow", key);
        Mono<Integer> b = sendForStatus("/r/slow", key);

        var statuses = Mono.zip(a, b).block(Duration.ofSeconds(20));

        assertThat(statuses).isNotNull();
        assertThat(statuses.getT1()).isEqualTo(201);
        assertThat(statuses.getT2()).isEqualTo(201);
        assertThat(controller.slowCount())
                .as("exactly one of the two concurrent duplicates may execute the handler")
                .isEqualTo(1);
    }

    private Mono<Integer> sendForStatus(String path, String key) {
        return Mono.fromCallable(() -> post(path, key, "{}").exchange()
                        .returnResult(String.class).getStatus().value())
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    // Lost claim (issue #19): the servlet matrix's sequences, through the reactive aspect.

    private static final Duration SHORT_CLAIM = Duration.ofMillis(300);

    /** One scripted invocation of {@code /r/lease}: signals when it starts, waits to be released. */
    static final class LeaseStep {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final boolean fail5xx;

        LeaseStep(boolean fail5xx) {
            this.fail5xx = fail5xx;
        }

        LeaseStep unblocked() {
            release.countDown();
            return this;
        }
    }

    private EntityExchangeResult<String> lease(String key) {
        return post("/r/lease", key, "{}").exchange().expectBody(String.class).returnResult();
    }

    @Test
    void lostClaim_sequenceA_lateCompletionDoesNotOverwriteWinnersResponse() throws Exception {
        String key = "wf-lost-a-" + System.nanoTime();
        var a = new LeaseStep(false);
        controller.armLease(a, new LeaseStep(false).unblocked());

        Duration originalClaimTtl = props.getClaimTtl();
        props.setClaimTtl(SHORT_CLAIM);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<EntityExchangeResult<String>> runA = pool.submit(() -> lease(key));
            assertThat(a.started.await(10, TimeUnit.SECONDS)).as("run A must have claimed the key").isTrue();

            Thread.sleep(SHORT_CLAIM.toMillis() * 3); // A's claim is now expired, A still running

            var b = lease(key);
            assertThat(b.getStatus().value()).isEqualTo(201);
            assertThat(b.getResponseBody()).contains("\"run\":2");

            a.release.countDown();
            assertThat(runA.get(10, TimeUnit.SECONDS).getStatus().value()).isEqualTo(201);

            var replay = lease(key);
            assertThat(replay.getStatus().value()).isEqualTo(201);
            assertThat(replay.getResponseBody())
                    .as("a replay must return what client B was told, not what the run that lost "
                            + "its claim produced afterwards")
                    .isEqualTo(b.getResponseBody());
            assertThat(controller.leaseCount()).isEqualTo(2);
        } finally {
            a.release.countDown();
            pool.shutdownNow();
            props.setClaimTtl(originalClaimTtl);
        }
    }

    @Test
    void lostClaim_sequenceB_lateReleaseDoesNotDeleteWinnersClaim() throws Exception {
        String key = "wf-lost-b-" + System.nanoTime();
        var a = new LeaseStep(true);
        var b = new LeaseStep(false);
        controller.armLease(a, b, new LeaseStep(false).unblocked());

        Duration originalClaimTtl = props.getClaimTtl();
        ConflictPolicy originalConflict = props.getOnConflict();
        props.setClaimTtl(SHORT_CLAIM);
        // The reactive aspect takes the policy from properties only. FAIL_FAST so C answers at once.
        props.setOnConflict(ConflictPolicy.FAIL_FAST);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<EntityExchangeResult<String>> runA = pool.submit(() -> lease(key));
            assertThat(a.started.await(10, TimeUnit.SECONDS)).as("run A must have claimed the key").isTrue();

            Thread.sleep(SHORT_CLAIM.toMillis() * 3); // A's claim is now expired, A still running

            props.setClaimTtl(Duration.ofMinutes(5));
            Future<EntityExchangeResult<String>> runB = pool.submit(() -> lease(key));
            assertThat(b.started.await(10, TimeUnit.SECONDS)).as("run B must have reclaimed the key").isTrue();

            a.release.countDown(); // A now fails with a 5xx, which releases the key
            assertThat(runA.get(10, TimeUnit.SECONDS).getStatus().value()).isEqualTo(500);

            assertThat(lease(key).getStatus().value()).isEqualTo(409);
            assertThat(controller.leaseCount())
                    .as("B still holds the key, so C must be told it is in flight - not run a third time")
                    .isEqualTo(2);

            b.release.countDown();
            assertThat(runB.get(10, TimeUnit.SECONDS).getStatus().value()).isEqualTo(201);
        } finally {
            a.release.countDown();
            b.release.countDown();
            pool.shutdownNow();
            props.setClaimTtl(originalClaimTtl);
            props.setOnConflict(originalConflict);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ReactiveApp {
        @Bean
        ReactiveController reactiveController() {
            return new ReactiveController();
        }

        /**
         * spring-boot-starter-security is on this module's test classpath for the servlet security
         * test, and WebFlux's default chain applies CSRF to every POST - which returned 403 for
         * every request here, with nothing to do with idempotency. Declared explicitly rather than
         * excluded by autoconfiguration class name, because that name differs between Boot
         * generations and this suite has to run on both.
         */
        @Bean
        SecurityWebFilterChain permitEverything(ServerHttpSecurity http) {
            return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .authorizeExchange(ex -> ex.anyExchange().permitAll())
                    .build();
        }
    }

    @RestController
    static class ReactiveController {
        private final AtomicInteger echo = new AtomicInteger();
        private final AtomicInteger empty = new AtomicInteger();
        private final AtomicInteger flaky = new AtomicInteger();
        private final AtomicInteger bad = new AtomicInteger();
        private final AtomicInteger slow = new AtomicInteger();
        private final AtomicBoolean failNext = new AtomicBoolean();

        void reset() {
            echo.set(0);
            empty.set(0);
            flaky.set(0);
            bad.set(0);
            slow.set(0);
            failNext.set(false);
        }

        void failNext() {
            failNext.set(true);
        }

        int echoCount() {
            return echo.get();
        }

        int emptyCount() {
            return empty.get();
        }

        int flakyCount() {
            return flaky.get();
        }

        int badCount() {
            return bad.get();
        }

        int slowCount() {
            return slow.get();
        }

        @Idempotent
        @PostMapping("/r/echo")
        Mono<ResponseEntity<Map<String, Object>>> echo(@RequestBody Map<String, Object> body) {
            echo.incrementAndGet();
            return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(body));
        }

        @Idempotent
        @PostMapping("/r/empty")
        Mono<Void> emptyResponse(@RequestBody Map<String, Object> body) {
            empty.incrementAndGet();
            return Mono.empty();
        }

        @Idempotent
        @PostMapping("/r/flaky")
        Mono<ResponseEntity<Map<String, Object>>> flaky(@RequestBody Map<String, Object> body) {
            flaky.incrementAndGet();
            if (failNext.compareAndSet(true, false)) {
                return Mono.error(new InfrastructureException());
            }
            return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true)));
        }

        @Idempotent
        @PostMapping("/r/bad")
        Mono<ResponseEntity<Map<String, Object>>> bad(@RequestBody Map<String, Object> body) {
            bad.incrementAndGet();
            return Mono.error(new ClientException());
        }

        @Idempotent
        @PostMapping("/r/slow")
        Mono<ResponseEntity<Map<String, Object>>> slow(@RequestBody Map<String, Object> body) {
            slow.incrementAndGet();
            return Mono.delay(Duration.ofMillis(400))
                    .map(t -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true)));
        }

        private final AtomicInteger lease = new AtomicInteger();
        private volatile List<LeaseStep> leaseSteps = List.of();

        void armLease(LeaseStep... steps) {
            lease.set(0);
            leaseSteps = List.of(steps);
        }

        int leaseCount() {
            return lease.get();
        }

        /** Invocation n follows step n, off the event loop; the body names the run. */
        @Idempotent
        @PostMapping("/r/lease")
        Mono<ResponseEntity<Map<String, Object>>> lease(@RequestBody Map<String, Object> body) {
            return Mono.fromCallable(() -> {
                int run = lease.incrementAndGet();
                var steps = leaseSteps;
                if (run <= steps.size()) {
                    LeaseStep step = steps.get(run - 1);
                    step.started.countDown();
                    step.release.await(30, TimeUnit.SECONDS);
                    if (step.fail5xx) {
                        throw new InfrastructureException();
                    }
                }
                return ResponseEntity.status(HttpStatus.CREATED).body(Map.<String, Object>of("run", run));
            }).subscribeOn(Schedulers.boundedElastic());
        }

        // ResponseStatusException rather than a plain exception annotated @ResponseStatus.
        // WebFlux maps this shape unconditionally, whereas annotation-driven status resolution
        // depends on which error handler the application has configured - and when it is not in
        // play everything becomes a default 500, which made the 5xx case below pass for entirely
        // the wrong reason. These tests are about the failure policy (5xx releases, 4xx is kept
        // and replayed), so the status has to be unambiguous.
        static class InfrastructureException extends ResponseStatusException {
            InfrastructureException() {
                super(HttpStatus.INTERNAL_SERVER_ERROR, "boom");
            }
        }

        static class ClientException extends ResponseStatusException {
            ClientException() {
                super(HttpStatus.BAD_REQUEST, "nope");
            }
        }
    }
}
