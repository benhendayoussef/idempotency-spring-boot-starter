package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.benhendayoussef.idempotency.api.ConflictPolicy;
import io.github.benhendayoussef.idempotency.api.Idempotent;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord.State;
import io.github.benhendayoussef.idempotency.api.IdempotencyScope;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties;
import io.github.benhendayoussef.idempotency.internal.IdempotencyKeyComposer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The full behavioural matrix, run through the real HTTP/AOP/MockMvc stack against a real store
 * (never mocked). Concrete subclasses ({@code RedisIdempotencyBehaviorTest},
 * {@code JdbcIdempotencyBehaviorTest}) supply the store via Testcontainers and only differ in
 * {@code idempotency.store} - every test method here runs identically against both.
 *
 * <p>Store-unreachable cases are not here: they require permanently breaking the connection, which
 * would corrupt every other case sharing this context. See
 * {@code RedisStoreUnavailableBehaviorTest} / {@code JdbcStoreUnavailableBehaviorTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = AbstractIdempotencyBehaviorTest.TestApp.class)
@AutoConfigureMockMvc
abstract class AbstractIdempotencyBehaviorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MatrixController controller;

    @Autowired
    private IdempotencyProperties props;

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private IdempotencyKeyComposer composer;

    @BeforeEach
    void resetController() {
        controller.reset();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private String newKey() {
        return "k-" + System.nanoTime();
    }

    // --- Row 1/2: header presence vs idempotency.require-key ------------------------------

    @Test
    void row1_noHeaderRequireKeyFalse_passesThroughAndExecutesEachTime() throws Exception {
        mockMvc.perform(post("/m/echo").contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/m/echo").contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());

        assertThat(controller.echoCount()).isEqualTo(2);
    }

    @Test
    void row2_noHeaderRequireKeyTrue_returns400() throws Exception {
        props.setRequireKey(true);
        try {
            mockMvc.perform(post("/m/echo").contentType("application/json").content("{\"a\":1}"))
                    .andExpect(status().isBadRequest());
            assertThat(controller.echoCount()).isZero();
        } finally {
            props.setRequireKey(false);
        }
    }

    // --- Row 3/4: first call executes, duplicate replays byte-identical body ---------------

    @Test
    void row3and4_firstCallExecutesDuplicateReplaysIdenticalBody() throws Exception {
        String key = newKey();
        String first = mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(controller.echoCount()).isEqualTo(1);
    }

    // --- Row 5: duplicate in flight, WAIT -> blocks then replays, handler invoked once -----

    @Test
    void row5_duplicateInFlightWait_blocksThenReplaysHandlerInvokedOnce() throws Exception {
        String key = newKey();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        controller.armBlockingWait(started, release);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> mockMvc.perform(post("/m/wait")
                            .header("Idempotency-Key", key).contentType("application/json").content("{}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString());

            assertThat(started.await(10, TimeUnit.SECONDS)).as("first call must have claimed the key").isTrue();

            Future<org.springframework.test.web.servlet.MvcResult> second = pool.submit(() -> mockMvc.perform(post("/m/wait")
                            .header("Idempotency-Key", key).contentType("application/json").content("{}"))
                    .andReturn());

            Thread.sleep(200); // let the second call actually enter its poll loop
            release.countDown();

            String firstBody = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(secondResult.getResponse().getStatus()).isEqualTo(HttpStatus.CREATED.value());
            assertThat(secondResult.getResponse().getContentAsString()).isEqualTo(firstBody);
            assertThat(secondResult.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
            assertThat(controller.waitCount()).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }

    // --- Row 6: duplicate in flight, FAIL_FAST -> 409 + Retry-After ------------------------

    @Test
    void row6_duplicateInFlightFailFast_returns409WithRetryAfter() throws Exception {
        String key = newKey();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        controller.armBlockingFailFast(started, release);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> {
                try {
                    mockMvc.perform(post("/m/fail-fast").header("Idempotency-Key", key)
                            .contentType("application/json").content("{}")).andExpect(status().isCreated());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(post("/m/fail-fast").header("Idempotency-Key", key)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(header().exists("Retry-After"));

            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThat(controller.failFastCount()).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }

    // --- Retry-After scales with idempotency.wait-timeout rather than being a fixed value ----

    @Test
    void retryAfterOnConflictScalesWithWaitTimeoutInsteadOfBeingHardcoded() throws Exception {
        // Default wait-timeout (5s) -> 10% clamped to the 1s floor, so the fail-fast conflict test
        // above still sees "1" and doesn't need to change. A much longer
        // wait-timeout must produce a proportionally longer (but still capped) hint instead of
        // making every client retry at the same 1s cadence regardless of how long the in-flight
        // request is actually expected to take.
        Duration original = props.getWaitTimeout();
        props.setWaitTimeout(Duration.ofSeconds(30)); // 10% = 3s, within the [1s, 5s] clamp
        String key = newKey();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        controller.armBlockingFailFast(started, release);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> {
                try {
                    mockMvc.perform(post("/m/fail-fast").header("Idempotency-Key", key)
                            .contentType("application/json").content("{}")).andExpect(status().isCreated());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(post("/m/fail-fast").header("Idempotency-Key", key)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(header().string("Retry-After", "3"));

            release.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            props.setWaitTimeout(original);
        }
    }

    // --- Row 7: wait exceeds wait-timeout -> 409, no hang -----------------------------------

    @Test
    void row7_waitExceedsTimeout_returns409WithoutHanging() throws Exception {
        Duration original = props.getWaitTimeout();
        props.setWaitTimeout(Duration.ofMillis(300));
        try {
            String key = newKey();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1); // never released within the test
            controller.armBlockingWait(started, release);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                pool.submit(() -> {
                    try {
                        mockMvc.perform(post("/m/wait").header("Idempotency-Key", key)
                                .contentType("application/json").content("{}"));
                    } catch (Exception ignored) {
                        // the request is still in flight when the test ends; irrelevant to this assertion
                    }
                });
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

                long start = System.nanoTime();
                mockMvc.perform(post("/m/wait").header("Idempotency-Key", key)
                                .contentType("application/json").content("{}"))
                        .andExpect(status().isConflict());
                long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

                assertThat(elapsedMs).as("must give up close to wait-timeout, not hang").isLessThan(5000);
            } finally {
                release.countDown();
                pool.shutdown();
            }
        } finally {
            props.setWaitTimeout(original);
        }
    }

    // --- Row 8: same key, different body -> 422, original record untouched ----------------

    @Test
    void row8_sameKeyDifferentBody_returns422AndOriginalRecordUntouched() throws Exception {
        String key = newKey();
        String first = mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":999}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(controller.echoCount()).isEqualTo(1);

        // "original record untouched": a third call with the ORIGINAL body must still replay
        // the original response, proving the mismatched attempt didn't corrupt the stored record.
        String third = mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(third).isEqualTo(first);
        assertThat(controller.echoCount()).isEqualTo(1);
    }

    // --- Row 9: same key, different user, scope=USER -> both execute independently --------

    @Test
    void row9_sameKeyDifferentUserScopeUser_bothExecuteIndependently() throws Exception {
        // No Spring Security filter chain is configured in this test app (no @EnableWebSecurity),
        // so SecurityMockMvcRequestPostProcessors.user(...) has nothing to populate
        // SecurityContextHolder from. MockMvc dispatches synchronously on this thread, so setting
        // the context directly here is visible to the aspect during the call - and is exactly
        // what PrincipalScopeResolver reads (see IdempotencyKeyComposerTest's route/namespace
        // isolation for the analogous unit-level proof).
        String key = newKey();
        try {
            authenticateAs("alice");
            mockMvc.perform(post("/m/user-scope").header("Idempotency-Key", key)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isCreated());

            authenticateAs("bob");
            mockMvc.perform(post("/m/user-scope").header("Idempotency-Key", key)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isCreated());
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(controller.userScopeCount()).isEqualTo(2);
    }

    private void authenticateAs(String principal) {
        var auth = new org.springframework.security.authentication.TestingAuthenticationToken(principal, null);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // --- Unauthenticated request under scope=USER must not 500 ---------------------------
    // SecurityContextHolder is left untouched (no authenticateAs call) - PrincipalScopeResolver.
    // namespace() throws IllegalStateException exactly as it would in production for a request
    // that reaches an @Idempotent(scope = USER) endpoint with no resolvable principal (e.g. a
    // public endpoint on an app that also has authenticated ones). idempotency.on-missing-principal
    // decides what happens next; none of the three values may let that exception surface raw.

    @Test
    void unauthenticatedUserScopeOnMissingPrincipalGlobal_fallsBackToGlobalNamespaceAndReplays() throws Exception {
        props.setOnMissingPrincipal(IdempotencyProperties.OnMissingPrincipal.GLOBAL);
        String key = newKey();
        try {
            mockMvc.perform(post("/m/user-scope").header("Idempotency-Key", key)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("Idempotent-Replay"));

            // Same key, still unauthenticated: falls back to the same global namespace both times,
            // so this is a genuine replay, not a fresh execution - proving the fallback is
            // consistent, not just "didn't crash".
            mockMvc.perform(post("/m/user-scope").header("Idempotency-Key", key)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replay", "true"));
        } finally {
            SecurityContextHolder.clearContext();
            props.setOnMissingPrincipal(IdempotencyProperties.OnMissingPrincipal.GLOBAL);
        }
        assertThat(controller.userScopeCount()).isEqualTo(1);
    }

    @Test
    void unauthenticatedUserScopeOnMissingPrincipalSkip_executesUnprotectedEachTime() throws Exception {
        props.setOnMissingPrincipal(IdempotencyProperties.OnMissingPrincipal.SKIP);
        String key = newKey();
        try {
            mockMvc.perform(post("/m/user-scope").header("Idempotency-Key", key)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("Idempotent-Replay"));

            // SKIP bypasses idempotency entirely for this request - no claim was ever made, so the
            // same key executes again instead of replaying.
            mockMvc.perform(post("/m/user-scope").header("Idempotency-Key", key)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("Idempotent-Replay"));
        } finally {
            SecurityContextHolder.clearContext();
            props.setOnMissingPrincipal(IdempotencyProperties.OnMissingPrincipal.GLOBAL);
        }
        assertThat(controller.userScopeCount()).isEqualTo(2);
    }

    @Test
    void unauthenticatedUserScopeOnMissingPrincipalReject_returns400ProblemDetailNotRaw500() throws Exception {
        props.setOnMissingPrincipal(IdempotencyProperties.OnMissingPrincipal.REJECT);
        try {
            mockMvc.perform(post("/m/user-scope").header("Idempotency-Key", newKey())
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Authenticated principal required")));
        } finally {
            SecurityContextHolder.clearContext();
            props.setOnMissingPrincipal(IdempotencyProperties.OnMissingPrincipal.GLOBAL);
        }
        assertThat(controller.userScopeCount()).isZero();
    }

    // --- Row 10: same key, different endpoint -> both execute independently ----------------

    @Test
    void row10_sameKeyDifferentEndpoint_bothExecuteIndependently() throws Exception {
        String key = newKey();
        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/m/echo-other").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());

        assertThat(controller.echoCount()).isEqualTo(1);
        assertThat(controller.echoOtherCount()).isEqualTo(1);
    }

    // --- Row 11/12: already covered structurally in IdempotencyAspectMockMvcTest (in-memory
    // store); re-verified here against the real store to prove the release/keep behaviour holds
    // independent of which IdempotencyStore backs it. ---------------------------------------

    @Test
    void row11_handlerThrows500_keyReleasedRetryReExecutesAndCanSucceed() throws Exception {
        String key = newKey();
        controller.armFailNextInfra();
        mockMvc.perform(post("/m/flaky").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isInternalServerError());

        mockMvc.perform(post("/m/flaky").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated());

        assertThat(controller.flakyCount()).isEqualTo(2);
    }

    @Test
    void row12_handlerThrows4xx_keyKeptRetryReplaysSame4xx() throws Exception {
        String key = newKey();
        mockMvc.perform(post("/m/fail4xx").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/m/fail4xx").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(controller.fail4xxCount()).isEqualTo(1);
    }

    // --- Row 13: TTL expires -> re-executes -------------------------------------------------

    @Test
    void row13_ttlExpires_reExecutes() throws Exception {
        String key = newKey();
        mockMvc.perform(post("/m/ttl").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated());
        assertThat(controller.ttlCount()).isEqualTo(1);

        Thread.sleep(1500); // endpoint TTL is 1s

        mockMvc.perform(post("/m/ttl").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated());
        assertThat(controller.ttlCount()).isEqualTo(2);
    }

    // --- Row 16: void return type -> replay returns 200 empty, no NPE ----------------------

    @Test
    void row16_voidReturnType_replayReturns200EmptyNoException() throws Exception {
        String key = newKey();
        mockMvc.perform(post("/m/void").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/m/void").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));

        assertThat(controller.voidCount()).isEqualTo(1);
    }

    // --- Row 17: ResponseEntity<T> -> status and body preserved on replay ------------------

    @Test
    void row17_responseEntityStatusAndBodyPreservedOnReplay() throws Exception {
        String key = newKey();
        var firstResult = mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":7}"))
                .andExpect(status().isCreated())
                .andReturn();
        var secondResult = mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":7}"))
                .andReturn();

        assertThat(secondResult.getResponse().getStatus()).isEqualTo(firstResult.getResponse().getStatus());
        assertThat(secondResult.getResponse().getContentAsString())
                .isEqualTo(firstResult.getResponse().getContentAsString());
    }

    // --- Row 18: generic collection return type deserializes correctly on replay -----------

    @Test
    void row18_genericCollectionReturnType_deserializesCorrectlyOnReplay() throws Exception {
        String key = newKey();
        String first = mockMvc.perform(post("/m/list").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/m/list").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(second).contains("\"name\":\"one\"").contains("\"name\":\"two\"");
        assertThat(controller.listCount()).isEqualTo(1);
    }

    // --- Row 19: stored payloadType no longer matches the method signature -> re-executes --

    @Test
    void row19_storedPayloadTypeMismatch_reExecutesCleanlyNoDeserializationCrash() throws Exception {
        String key = newKey();
        MockHttpServletRequest fakeRequest = new MockHttpServletRequest("POST", "/m/echo");
        fakeRequest.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/m/echo");
        String storageKey = composer.compose(key, "", fakeRequest);

        store.claim(storageKey, "", Duration.ofMinutes(5));
        var staleRecord = new IdempotencyRecord(
                State.COMPLETED, "", 201, "java.lang.String", "\"stale\"", Instant.now());
        store.complete(storageKey, staleRecord, Duration.ofMinutes(5));

        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());

        assertThat(controller.echoCount()).isEqualTo(1);
    }

    // --- Row 20: response body exceeds max-payload-size -> not stored, request still succeeds

    @Test
    void row20_responseExceedsMaxPayloadSize_notStoredRequestStillSucceeds() throws Exception {
        var original = props.getMaxPayloadSize();
        props.setMaxPayloadSize(org.springframework.util.unit.DataSize.ofBytes(1));
        try {
            String key = newKey();
            mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                            .contentType("application/json").content("{\"a\":1}"))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                            .contentType("application/json").content("{\"a\":1}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("Idempotent-Replay"));

            assertThat(controller.echoCount()).isEqualTo(2);
        } finally {
            props.setMaxPayloadSize(original);
        }
    }

    // --- Row 21: argument order in a JSON map differs -> fingerprints match, replay happens

    @Test
    void row21_argumentOrderInMapDiffers_fingerprintsMatchReplayHappens() throws Exception {
        String key = newKey();
        String first = mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1,\"b\":2}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"b\":2,\"a\":1}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"));

        assertThat(controller.echoCount()).isEqualTo(1);
    }

    // ==========================================================================================
    // Concurrency soak. This is the check that decides whether the library is correct.
    // ==========================================================================================

    /** One full run of the N=32-identical-requests race; returns true iff every assertion held. */
    private boolean runConcurrencySoakIteration() throws Exception {
        controller.reset();
        String key = "soak-" + System.nanoTime();
        int threads = 32;

        var start = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<org.springframework.test.web.servlet.MvcResult> results = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.countDown();
                    try {
                        go.await();
                        results.add(mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                                        .contentType("application/json").content("{\"soak\":1}"))
                                .andReturn());
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }));
            }
            start.await(10, TimeUnit.SECONDS);
            go.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        if (!errors.isEmpty() || results.size() != threads || controller.echoCount() != 1) {
            return false;
        }
        String firstBody = results.get(0).getResponse().getContentAsString();
        for (var r : results) {
            if (r.getResponse().getStatus() != HttpStatus.CREATED.value()) {
                return false;
            }
            if (!r.getResponse().getContentAsString().equals(firstBody)) {
                return false;
            }
        }
        return true;
    }

    @Test
    void concurrency_32IdenticalConcurrentRequests_handlerInvokedExactlyOnceAllResponsesIdentical2xx()
            throws Exception {
        assertThat(runConcurrencySoakIteration())
                .as("handler invoked exactly once, all 32 responses identical, all 2xx")
                .isTrue();
    }

    /**
     * The check that decides whether the library is correct: a race that passes once and fails on
     * run 37 is still a bug. 50 iterations reusing the same context/store/container - equivalent
     * statistical coverage to 50 separate {@code --rerun-tasks} invocations for catching a flaky
     * race, without paying JVM+Testcontainers startup cost 50 times over. Running this task a few
     * times with {@code --rerun-tasks} is worth doing as a cross-JVM-restart sanity check on top.
     */
    @Test
    void concurrency_50IterationSoak_zeroFailuresAcrossAllRuns() throws Exception {
        int iterations = 50;
        int failures = 0;
        for (int i = 0; i < iterations; i++) {
            if (!runConcurrencySoakIteration()) {
                failures++;
            }
        }
        assertThat(failures).as("%d/%d soak iterations failed - any non-zero count is a BLOCKER, not flakiness",
                failures, iterations).isZero();
    }

    @Test
    void concurrency_32DifferentKeys_all32ExecuteNotAGlobalLock() throws Exception {
        controller.reset();
        int threads = 32;
        var start = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<org.springframework.test.web.servlet.MvcResult>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                String key = "distinct-" + i + "-" + System.nanoTime();
                futures.add(pool.submit(() -> {
                    start.countDown();
                    go.await();
                    return mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                                    .contentType("application/json").content("{\"a\":1}"))
                            .andReturn();
                }));
            }
            start.await(10, TimeUnit.SECONDS);
            go.countDown();
            for (var f : futures) {
                assertThat(f.get(30, TimeUnit.SECONDS).getResponse().getStatus())
                        .isEqualTo(HttpStatus.CREATED.value());
            }
        } finally {
            pool.shutdown();
        }

        assertThat(controller.echoCount()).as("32 distinct keys must not serialize behind a global lock")
                .isEqualTo(32);
    }

    @Test
    void concurrency_aspectOverheadVersusUnannotatedEndpoint_reportsP50P99() throws Exception {
        int warmup = 20;
        int samples = 200;

        for (int i = 0; i < warmup; i++) {
            mockMvc.perform(post("/m/plain").contentType("application/json").content("{\"a\":1}"));
            mockMvc.perform(post("/m/echo").header("Idempotency-Key", newKey())
                    .contentType("application/json").content("{\"a\":1}"));
        }

        long[] plainNanos = new long[samples];
        long[] idempotentNanos = new long[samples];
        for (int i = 0; i < samples; i++) {
            long s0 = System.nanoTime();
            mockMvc.perform(post("/m/plain").contentType("application/json").content("{\"a\":1}"));
            plainNanos[i] = System.nanoTime() - s0;

            long s1 = System.nanoTime();
            mockMvc.perform(post("/m/echo").header("Idempotency-Key", newKey())
                    .contentType("application/json").content("{\"a\":1}"));
            idempotentNanos[i] = System.nanoTime() - s1;
        }

        java.util.Arrays.sort(plainNanos);
        java.util.Arrays.sort(idempotentNanos);
        double plainP50 = plainNanos[samples / 2] / 1_000_000.0;
        double plainP99 = plainNanos[(int) (samples * 0.99)] / 1_000_000.0;
        double idemP50 = idempotentNanos[samples / 2] / 1_000_000.0;
        double idemP99 = idempotentNanos[(int) (samples * 0.99)] / 1_000_000.0;

        System.out.printf(java.util.Locale.US,
                "OVERHEAD-PROBE store=%s plain(p50=%.3fms,p99=%.3fms) idempotent(p50=%.3fms,p99=%.3fms) overhead(p50=%.3fms,p99=%.3fms)%n",
                props.getStore(), plainP50, plainP99, idemP50, idemP99, idemP50 - plainP50, idemP99 - plainP99);
    }

    // --- Configuration used by every concrete subclass --------------------------------------

    // Deliberately not @SpringBootApplication: its default @ComponentScan would also discover
    // the nested @RestController classes below by classpath scanning, on top of the explicit
    // @Bean registration, producing a duplicate bean and an "ambiguous mapping" startup failure.
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        MatrixController matrixController() {
            return new MatrixController();
        }
    }

    @RestController
    static class MatrixController {
        final AtomicInteger echoCount = new AtomicInteger();
        final AtomicInteger echoOtherCount = new AtomicInteger();
        final AtomicInteger waitCount = new AtomicInteger();
        final AtomicInteger failFastCount = new AtomicInteger();
        final AtomicInteger flakyCount = new AtomicInteger();
        final AtomicInteger fail4xxCount = new AtomicInteger();
        final AtomicInteger ttlCount = new AtomicInteger();
        final AtomicInteger userScopeCount = new AtomicInteger();
        final AtomicInteger voidCount = new AtomicInteger();
        final AtomicInteger listCount = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean failNextInfra = new java.util.concurrent.atomic.AtomicBoolean();

        private volatile CountDownLatch waitStarted;
        private volatile CountDownLatch waitRelease;
        private volatile CountDownLatch failFastStarted;
        private volatile CountDownLatch failFastRelease;

        void armBlockingWait(CountDownLatch started, CountDownLatch release) {
            this.waitStarted = started;
            this.waitRelease = release;
        }

        void armBlockingFailFast(CountDownLatch started, CountDownLatch release) {
            this.failFastStarted = started;
            this.failFastRelease = release;
        }

        void reset() {
            echoCount.set(0);
            echoOtherCount.set(0);
            waitCount.set(0);
            failFastCount.set(0);
            flakyCount.set(0);
            fail4xxCount.set(0);
            ttlCount.set(0);
            userScopeCount.set(0);
            voidCount.set(0);
            listCount.set(0);
            failNextInfra.set(false);
        }

        // Reads must go through these methods, not direct field access on the @Autowired
        // reference: every method here is @Idempotent, so the bean is CGLIB-proxied and the proxy
        // instance itself never runs field initializers (Spring uses Objenesis to create it) -
        // direct field access on the proxy reads an uninitialized copy, not the real target's
        // state. Method calls dispatch through the proxy to the real target correctly.
        int echoCount() {
            return echoCount.get();
        }

        int echoOtherCount() {
            return echoOtherCount.get();
        }

        int waitCount() {
            return waitCount.get();
        }

        int failFastCount() {
            return failFastCount.get();
        }

        int flakyCount() {
            return flakyCount.get();
        }

        int fail4xxCount() {
            return fail4xxCount.get();
        }

        int ttlCount() {
            return ttlCount.get();
        }

        int userScopeCount() {
            return userScopeCount.get();
        }

        int voidCount() {
            return voidCount.get();
        }

        int listCount() {
            return listCount.get();
        }

        void armFailNextInfra() {
            failNextInfra.set(true);
        }

        @Idempotent
        @PostMapping("/m/echo")
        ResponseEntity<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
            echoCount.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }

        // Not @Idempotent: the overhead measurement baseline. Identical shape to echo() otherwise, so
        // the only measured difference is the aspect itself, not JSON (de)serialization cost.
        @PostMapping("/m/plain")
        ResponseEntity<Map<String, Object>> plain(@RequestBody Map<String, Object> body) {
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }

        @Idempotent
        @PostMapping("/m/echo-other")
        ResponseEntity<Map<String, Object>> echoOther(@RequestBody Map<String, Object> body) {
            echoOtherCount.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }

        @Idempotent(onConflict = ConflictPolicy.WAIT)
        @PostMapping("/m/wait")
        ResponseEntity<Map<String, Object>> waitEndpoint(@RequestBody Map<String, Object> body) throws InterruptedException {
            waitCount.incrementAndGet();
            CountDownLatch s = waitStarted;
            CountDownLatch r = waitRelease;
            if (s != null) {
                s.countDown();
            }
            if (r != null) {
                r.await(30, TimeUnit.SECONDS);
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("done", true));
        }

        @Idempotent(onConflict = ConflictPolicy.FAIL_FAST)
        @PostMapping("/m/fail-fast")
        ResponseEntity<Map<String, Object>> failFastEndpoint(@RequestBody Map<String, Object> body) throws InterruptedException {
            failFastCount.incrementAndGet();
            CountDownLatch s = failFastStarted;
            CountDownLatch r = failFastRelease;
            if (s != null) {
                s.countDown();
            }
            if (r != null) {
                r.await(30, TimeUnit.SECONDS);
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("done", true));
        }

        @Idempotent
        @PostMapping("/m/flaky")
        ResponseEntity<Map<String, Object>> flaky(@RequestBody Map<String, Object> body) {
            flakyCount.incrementAndGet();
            if (failNextInfra.compareAndSet(true, false)) {
                throw new InfrastructureException();
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }

        @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        static class InfrastructureException extends RuntimeException {
        }

        @Idempotent
        @PostMapping("/m/fail4xx")
        ResponseEntity<Map<String, Object>> fail4xx(@RequestBody Map<String, Object> body) {
            fail4xxCount.incrementAndGet();
            throw new ClientException();
        }

        @Idempotent(ttl = "1s")
        @PostMapping("/m/ttl")
        ResponseEntity<Map<String, Object>> ttl(@RequestBody Map<String, Object> body) {
            ttlCount.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }

        @Idempotent(scope = IdempotencyScope.USER)
        @PostMapping("/m/user-scope")
        ResponseEntity<Map<String, Object>> userScope(@RequestBody Map<String, Object> body) {
            userScopeCount.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }

        @Idempotent
        @PostMapping("/m/void")
        void voidEndpoint(@RequestBody Map<String, Object> body) {
            voidCount.incrementAndGet();
        }

        @Idempotent
        @PostMapping("/m/list")
        ResponseEntity<List<Item>> list(@RequestBody Map<String, Object> body) {
            listCount.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(List.of(new Item("one"), new Item("two")));
        }

        @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.BAD_REQUEST)
        static class ClientException extends RuntimeException {
        }

        record Item(String name) {
        }
    }
}
