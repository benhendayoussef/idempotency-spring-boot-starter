package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.internal.InMemoryIdempotencyStore;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The claim is a lease on an in-flight request; the retention TTL is how long a finished response
 * stays replayable. Before 0.4 they were one value.
 *
 * <p>What that cost: a process that died mid-request left its key {@code IN_PROGRESS} for the whole
 * retention window - 24 hours by default. Every retry in that window got a 409, the sweeper could
 * not help (it only deletes rows already past expiry), and there was no supported way to clear it.
 * A pod restart during a payment meant that customer could not retry until the next day.
 *
 * <p>The assertions go through a recording store rather than through elapsed time. A timing test
 * here would be both slow and dishonest: a first attempt at this file simulated the crash by calling
 * {@code store.claim(...)} directly with the lease TTL, which meant the test passed whether or not
 * the aspect had been fixed at all. What actually needs pinning is the value the aspect hands to
 * {@code claim()} versus {@code complete()}, so that is what is captured.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {AbstractIdempotencyBehaviorTest.TestApp.class,
                ClaimLeaseBehaviorTest.RecordingStoreConfiguration.class},
        properties = {
                "idempotency.store=memory",
                // A retention window far longer than the lease - the shape that produced the defect.
                "idempotency.default-ttl=24h",
                "idempotency.claim-ttl=5m",
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE_AND_SECURITY
        })
@Import(MockMvcTestConfiguration.class)
class ClaimLeaseBehaviorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecordingStore store;

    @Autowired
    private AbstractIdempotencyBehaviorTest.MatrixController controller;

    @BeforeEach
    void reset() {
        controller.reset();
        store.reset();
    }

    @Test
    void theClaimGetsTheLeaseAndTheCompletionGetsTheRetentionWindow() throws Exception {
        mockMvc.perform(post("/m/echo").header("Idempotency-Key", "lease-" + System.nanoTime())
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());

        assertThat(store.claimTtl.get())
                .as("the claim is a lease on an in-flight request - handing it the retention TTL is "
                        + "what locked a key for 24 hours when a process died mid-request")
                .isEqualTo(Duration.ofMinutes(5));

        assertThat(store.completeTtl.get())
                .as("the completed response must still be retained for the full window")
                .isEqualTo(Duration.ofHours(24));
    }

    /**
     * The cap. Asking for a 30-second idempotency window should not leave a dead claim sitting for
     * five minutes - and the cap is also what guarantees this property can never hold a claim
     * <em>longer</em> than 0.3 did, which is what makes it safe to turn on by default.
     */
    @Test
    void aRetentionWindowShorterThanTheLeaseCapsTheLease() throws Exception {
        // /m/ttl is annotated @Idempotent(ttl = "1s").
        mockMvc.perform(post("/m/ttl").header("Idempotency-Key", "capped-" + System.nanoTime())
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());

        assertThat(store.claimTtl.get())
                .as("the lease must never outlive the retention window it belongs to")
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(store.completeTtl.get()).isEqualTo(Duration.ofSeconds(1));
    }

    /**
     * The regression a careless fix would introduce: shortening the lease must not shorten how long
     * a real response stays replayable. That failure would be much harder to notice than the stuck
     * key it replaced, and worse - a silently expiring idempotency window.
     */
    @Test
    void aCompletedResponseStillReplays() throws Exception {
        String key = "retained-" + System.nanoTime();

        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"));

        assertThat(controller.echoCount()).isEqualTo(1);
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingStoreConfiguration {
        @Bean
        RecordingStore idempotencyStore() {
            return new RecordingStore();
        }
    }

    /**
     * Delegates to the real in-memory store and records the durations it was handed. Decorating
     * rather than mocking keeps every request genuinely working end to end, so the surrounding
     * assertions on status and replay still mean something.
     */
    static class RecordingStore implements IdempotencyStore {

        private final InMemoryIdempotencyStore delegate = new InMemoryIdempotencyStore();
        final AtomicReference<Duration> claimTtl = new AtomicReference<>();
        final AtomicReference<Duration> completeTtl = new AtomicReference<>();

        void reset() {
            claimTtl.set(null);
            completeTtl.set(null);
        }

        @Override
        public ClaimResult claim(String key, String fingerprint, Duration ttl) {
            claimTtl.set(ttl);
            return delegate.claim(key, fingerprint, ttl);
        }

        @Override
        public void complete(String key, IdempotencyRecord record, Duration ttl) {
            completeTtl.set(ttl);
            delegate.complete(key, record, ttl);
        }

        @Override
        public void release(String key) {
            delegate.release(key);
        }

        @Override
        public Optional<IdempotencyRecord> find(String key) {
            return delegate.find(key);
        }
    }
}
