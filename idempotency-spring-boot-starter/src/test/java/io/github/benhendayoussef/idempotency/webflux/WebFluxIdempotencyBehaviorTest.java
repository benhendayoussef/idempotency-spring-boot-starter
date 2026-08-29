package io.github.benhendayoussef.idempotency.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.benhendayoussef.idempotency.api.Idempotent;
import io.github.benhendayoussef.idempotency.behavior.TestAutoconfigExcludes;
import java.time.Duration;
import java.util.Map;
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
