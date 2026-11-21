package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.benhendayoussef.idempotency.api.ConflictPolicy;
import io.github.benhendayoussef.idempotency.api.Idempotent;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord.State;
import io.github.benhendayoussef.idempotency.api.IdempotencyKeys;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.internal.IdempotencyAspect;
import io.github.benhendayoussef.idempotency.internal.filter.IdempotencyFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.web.servlet.MvcResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code idempotency.mode=filter}: byte-exact replay.
 *
 * <p>The tests that matter here are the ones aspect mode <strong>cannot</strong> pass - a body
 * written straight to the {@code HttpServletResponse}, and headers the handler set that are not part
 * of any return value. Those are the documented reasons this mode exists, so proving replay works
 * for an ordinary {@code ResponseEntity} would say almost nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = FilterModeBehaviorTest.FilterApp.class,
        properties = {"idempotency.mode=filter", "idempotency.store=memory",
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE_AND_SECURITY})
@Import(MockMvcTestConfiguration.class)
class FilterModeBehaviorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RawController controller;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private IdempotencyProperties props;

    @BeforeEach
    void reset() {
        controller.reset();
    }

    /**
     * The modes must never both be active: each would claim the same key for the same request, and
     * the second would see the first one holding it.
     */
    @Test
    void filterModeReplacesTheAspectRatherThanRunningAlongsideIt() {
        assertThat(context.getBeanNamesForType(IdempotencyFilter.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(IdempotencyAspect.class))
                .as("the aspect must be absent in filter mode")
                .isEmpty();
    }

    /**
     * The headline case. This handler writes bytes directly to the response and returns void, so
     * there is no return value for aspect mode to capture - the README lists it as a limitation.
     * Filter mode records the actual bytes, so the replay is identical.
     */
    @Test
    void aBodyWrittenDirectlyToTheResponseIsReplayedByteForByte() throws Exception {
        String key = "raw-" + System.nanoTime();

        String first = mockMvc.perform(post("/f/raw").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String replayed = mockMvc.perform(post("/f/raw").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(replayed).isEqualTo(first);
        assertThat(first).isEqualTo("written-straight-to-the-response");
        assertThat(controller.rawCount())
                .as("the duplicate must be answered from the store, not re-executed")
                .isEqualTo(1);
    }

    /** A header the handler set is part of the response, and must survive a replay. */
    @Test
    void headersOnTheAllowlistAreReplayed() throws Exception {
        String key = "hdr-" + System.nanoTime();

        mockMvc.perform(post("/f/created").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/orders/42"));

        mockMvc.perform(post("/f/created").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/orders/42"))
                .andExpect(header().string("Idempotent-Replay", "true"));

        assertThat(controller.createdCount()).isEqualTo(1);
    }

    /**
     * The allowlist is a safety property, not a tuning knob. Replaying {@code Set-Cookie} would hand
     * a second caller the first caller's session.
     */
    @Test
    void headersOffTheAllowlistAreNotReplayed() throws Exception {
        String key = "cookie-" + System.nanoTime();

        mockMvc.perform(post("/f/cookie").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", "SESSION=secret-for-caller-one"));

        mockMvc.perform(post("/f/cookie").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void theStatusCodeIsReplayedExactly() throws Exception {
        String key = "status-" + System.nanoTime();

        mockMvc.perform(post("/f/created").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/f/created").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated());
    }

    @Test
    void a5xxReleasesTheKeySoARetryReExecutes() throws Exception {
        String key = "5xx-" + System.nanoTime();
        controller.failNext();

        mockMvc.perform(post("/f/flaky").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isInternalServerError());
        mockMvc.perform(post("/f/flaky").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        assertThat(controller.flakyCount()).isEqualTo(2);
    }

    @Test
    void a4xxIsKeptAndReplayed() throws Exception {
        String key = "4xx-" + System.nanoTime();

        mockMvc.perform(post("/f/bad").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/f/bad").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(controller.badCount())
                .as("a deterministic client error is replayed, not re-executed")
                .isEqualTo(1);
    }

    /** An endpoint without the annotation must be left completely alone, key header or not. */
    @Test
    void anUnannotatedEndpointIsUntouched() throws Exception {
        String key = "plain-" + System.nanoTime();

        mockMvc.perform(post("/f/plain").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replay"));
        mockMvc.perform(post("/f/plain").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        assertThat(controller.plainCount()).isEqualTo(2);
    }

    @Test
    void aRequestWithNoKeyExecutesEveryTime() throws Exception {
        mockMvc.perform(post("/f/created").contentType("application/json").content("{}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/f/created").contentType("application/json").content("{}"))
                .andExpect(status().isCreated());

        assertThat(controller.createdCount()).isEqualTo(2);
    }

    /**
     * A record left behind by aspect mode holds a serialized return value, not a captured response.
     * Replaying it used to fail with a Jackson error; it must be refused cleanly instead.
     */
    @Test
    void aRecordWrittenByAspectModeIsRefusedNotDeserialized() throws Exception {
        String key = "aspect-" + System.nanoTime();
        String storeKey = IdempotencyKeys.storageKey(key, "POST", "/f/created", "");
        store.claim(storeKey, "some-aspect-fingerprint", Duration.ofMinutes(1));
        store.complete(storeKey, new IdempotencyRecord(State.COMPLETED, "some-aspect-fingerprint", 200,
                "com.example.MockSignRequest", "{\"name\":\"user field\"}", Instant.now()),
                Duration.ofMinutes(1));

        mockMvc.perform(post("/f/created").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict());

        assertThat(controller.createdCount()).isZero();
    }

    // Lost claim (issue #19): the sequences AbstractIdempotencyBehaviorTest runs against the aspect,
    // here through the filter, which settles the key on its own code path.

    private static final Duration SHORT_CLAIM = Duration.ofMillis(300);

    @Test
    void lostClaim_sequenceA_lateCompletionDoesNotOverwriteWinnersResponse() throws Exception {
        String key = "lost-a-" + System.nanoTime();
        var a = new AbstractIdempotencyBehaviorTest.LeaseStep(false);
        controller.armLease(a, new AbstractIdempotencyBehaviorTest.LeaseStep(false).unblocked());

        Duration originalClaimTtl = props.getClaimTtl();
        props.setClaimTtl(SHORT_CLAIM);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> runA = pool.submit(() -> mockMvc.perform(post("/f/lease")
                    .header("Idempotency-Key", key).contentType("application/json").content("{}")).andReturn());
            assertThat(a.started.await(10, TimeUnit.SECONDS)).as("run A must have claimed the key").isTrue();

            Thread.sleep(SHORT_CLAIM.toMillis() * 3); // A's claim is now expired, A still running

            String bodyB = mockMvc.perform(post("/f/lease").header("Idempotency-Key", key)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("Idempotent-Replay"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(bodyB).contains("\"run\":2");

            a.release.countDown();
            assertThat(runA.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);

            String replay = mockMvc.perform(post("/f/lease").header("Idempotency-Key", key)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replay", "true"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(replay)
                    .as("a replay must return what client B was told, not what the run that lost "
                            + "its claim produced afterwards")
                    .isEqualTo(bodyB);
            assertThat(controller.leaseCount()).isEqualTo(2);
        } finally {
            a.release.countDown();
            pool.shutdownNow();
            props.setClaimTtl(originalClaimTtl);
        }
    }

    @Test
    void lostClaim_sequenceB_lateReleaseDoesNotDeleteWinnersClaim() throws Exception {
        String key = "lost-b-" + System.nanoTime();
        var a = new AbstractIdempotencyBehaviorTest.LeaseStep(true);
        var b = new AbstractIdempotencyBehaviorTest.LeaseStep(false);
        controller.armLease(a, b, new AbstractIdempotencyBehaviorTest.LeaseStep(false).unblocked());

        Duration originalClaimTtl = props.getClaimTtl();
        ConflictPolicy originalConflict = props.getOnConflict();
        props.setClaimTtl(SHORT_CLAIM);
        // Filter mode takes the policy from properties only. FAIL_FAST so C answers 409 at once.
        props.setOnConflict(ConflictPolicy.FAIL_FAST);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> runA = pool.submit(() -> mockMvc.perform(post("/f/lease")
                    .header("Idempotency-Key", key).contentType("application/json").content("{}")).andReturn());
            assertThat(a.started.await(10, TimeUnit.SECONDS)).as("run A must have claimed the key").isTrue();

            Thread.sleep(SHORT_CLAIM.toMillis() * 3); // A's claim is now expired, A still running

            props.setClaimTtl(Duration.ofMinutes(5));
            Future<MvcResult> runB = pool.submit(() -> mockMvc.perform(post("/f/lease")
                    .header("Idempotency-Key", key).contentType("application/json").content("{}")).andReturn());
            assertThat(b.started.await(10, TimeUnit.SECONDS)).as("run B must have reclaimed the key").isTrue();

            a.release.countDown(); // A now answers 500, which releases the key
            assertThat(runA.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(500);

            mockMvc.perform(post("/f/lease").header("Idempotency-Key", key)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isConflict());

            assertThat(controller.leaseCount())
                    .as("B still holds the key, so C must be told it is in flight - not run a third time")
                    .isEqualTo(2);

            b.release.countDown();
            assertThat(runB.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
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
    static class FilterApp {
        @Bean
        RawController rawController() {
            return new RawController();
        }
    }

    @RestController
    static class RawController {
        private final AtomicInteger raw = new AtomicInteger();
        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger cookie = new AtomicInteger();
        private final AtomicInteger flaky = new AtomicInteger();
        private final AtomicInteger bad = new AtomicInteger();
        private final AtomicInteger plain = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean failNext =
                new java.util.concurrent.atomic.AtomicBoolean();

        void reset() {
            raw.set(0);
            created.set(0);
            cookie.set(0);
            flaky.set(0);
            bad.set(0);
            plain.set(0);
            failNext.set(false);
        }

        void failNext() {
            failNext.set(true);
        }

        int rawCount() {
            return raw.get();
        }

        int createdCount() {
            return created.get();
        }

        int flakyCount() {
            return flaky.get();
        }

        int badCount() {
            return bad.get();
        }

        int plainCount() {
            return plain.get();
        }

        /** Writes to the response and returns void: nothing for aspect mode to capture. */
        @Idempotent
        @PostMapping("/f/raw")
        void raw(@RequestBody Map<String, Object> body, HttpServletResponse response) throws IOException {
            raw.incrementAndGet();
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
            response.getOutputStream().write("written-straight-to-the-response".getBytes(StandardCharsets.UTF_8));
        }

        @Idempotent
        @PostMapping("/f/created")
        ResponseEntity<Map<String, Object>> created(@RequestBody Map<String, Object> body) {
            created.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED)
                    .header("Location", "/orders/42")
                    .body(Map.of("id", 42));
        }

        @Idempotent
        @PostMapping("/f/cookie")
        ResponseEntity<Map<String, Object>> withCookie(@RequestBody Map<String, Object> body) {
            cookie.incrementAndGet();
            return ResponseEntity.ok()
                    .header("Set-Cookie", "SESSION=secret-for-caller-one")
                    .body(Map.of("ok", true));
        }

        @Idempotent
        @PostMapping("/f/flaky")
        ResponseEntity<Map<String, Object>> flaky(@RequestBody Map<String, Object> body) {
            flaky.incrementAndGet();
            if (failNext.compareAndSet(true, false)) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("err", true));
            }
            return ResponseEntity.ok(Map.of("ok", true));
        }

        @Idempotent
        @PostMapping("/f/bad")
        ResponseEntity<Map<String, Object>> bad(@RequestBody Map<String, Object> body) {
            bad.incrementAndGet();
            return ResponseEntity.badRequest().body(Map.of("err", "nope"));
        }

        private final AtomicInteger lease = new AtomicInteger();
        private volatile java.util.List<AbstractIdempotencyBehaviorTest.LeaseStep> leaseSteps = java.util.List.of();

        void armLease(AbstractIdempotencyBehaviorTest.LeaseStep... steps) {
            lease.set(0);
            leaseSteps = java.util.List.of(steps);
        }

        int leaseCount() {
            return lease.get();
        }

        /**
         * Invocation n follows step n; the body names the run. A failing step answers 500 rather
         * than throwing, so the filter settles it through its release-on-5xx path.
         */
        @Idempotent
        @PostMapping("/f/lease")
        ResponseEntity<Map<String, Object>> lease(@RequestBody Map<String, Object> body) throws InterruptedException {
            int run = lease.incrementAndGet();
            var steps = leaseSteps;
            if (run <= steps.size()) {
                var step = steps.get(run - 1);
                step.started.countDown();
                step.release.await(30, TimeUnit.SECONDS);
                if (step.fail5xx) {
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("run", run));
                }
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("run", run));
        }

        @PostMapping("/f/plain")
        ResponseEntity<Map<String, Object>> plain(@RequestBody Map<String, Object> body) {
            plain.incrementAndGet();
            return ResponseEntity.ok(Map.of("ok", true));
        }
    }
}
