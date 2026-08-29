package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The counters must move for real requests through the real HTTP/AOP path, against a real
 * {@link MeterRegistry} - not a mock. A metrics implementation that compiles but never increments
 * is the classic way observability silently fails, and it fails quietly enough that nobody notices
 * until they need a dashboard during an incident.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {AbstractIdempotencyBehaviorTest.TestApp.class,
                MicrometerMetricsBehaviorTest.MeterRegistryConfiguration.class},
        properties = {"idempotency.store=memory",
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE_AND_SECURITY})
@Import(MockMvcTestConfiguration.class)
class MicrometerMetricsBehaviorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private AbstractIdempotencyBehaviorTest.MatrixController controller;

    private double count(String outcome) {
        var counter = registry.find("idempotency.requests").tag("outcome", outcome).counter();
        return counter == null ? 0d : counter.count();
    }

    @Test
    void firstCallCountsAsExecutedAndTheDuplicateAsReplayed() throws Exception {
        double executedBefore = count("executed");
        double replayedBefore = count("replayed");
        String key = "metrics-" + System.nanoTime();

        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());

        assertThat(count("executed") - executedBefore).isEqualTo(1d);
        assertThat(count("replayed") - replayedBefore).isZero();

        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());

        assertThat(count("replayed") - replayedBefore)
                .as("the duplicate is a replay, and must not also count as an execution").isEqualTo(1d);
        assertThat(count("executed") - executedBefore).isEqualTo(1d);
    }

    @Test
    void aRequestWithNoKeyCountsAsMissingKey() throws Exception {
        double before = count("missing_key");
        mockMvc.perform(post("/m/echo").contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());
        assertThat(count("missing_key") - before).isEqualTo(1d);
    }

    @Test
    void a5xxReleasesTheKeyAndCountsAsReleased() throws Exception {
        double before = count("released");
        String key = "metrics-released-" + System.nanoTime();

        // /m/flaky throws a 500-mapped exception on the armed call, which the failure policy
        // releases so a retry can re-execute.
        controller.armFailNextInfra();

        mockMvc.perform(post("/m/flaky").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isInternalServerError());

        assertThat(count("released") - before).isEqualTo(1d);
    }

    @Test
    void aDifferentBodyOnTheSameKeyCountsAsAFingerprintMismatch() throws Exception {
        double before = count("fingerprint_mismatch");
        String key = "metrics-fp-" + System.nanoTime();

        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":999}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(count("fingerprint_mismatch") - before).isEqualTo(1d);
    }

    /**
     * Every outcome shares one meter name, so a dashboard can express "replay rate" as a ratio over
     * a single series instead of hard-coding a list of metric names. Asserting the shape here means
     * a future outcome added as its own meter name breaks this test rather than silently splitting
     * the series.
     */
    @Test
    void allOutcomesShareASingleCounterNameDistinguishedByTag() throws Exception {
        mockMvc.perform(post("/m/echo").header("Idempotency-Key", "shape-" + System.nanoTime())
                .contentType("application/json").content("{\"a\":1}"));

        var meters = registry.find("idempotency.requests").counters();
        assertThat(meters).isNotEmpty();
        assertThat(meters)
                .allSatisfy(c -> assertThat(c.getId().getTag("outcome"))
                        .as("every idempotency.requests counter must carry an outcome tag")
                        .isNotNull());
        assertThat(registry.getMeters())
                .filteredOn(m -> m.getId().getName().startsWith("idempotency."))
                .allSatisfy(m -> assertThat(m.getId().getName())
                        .as("no idempotency meter may use a name other than the single shared counter")
                        .isEqualTo("idempotency.requests"));
    }

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfiguration {
        // A real registry, not a mock: SimpleMeterRegistry actually accumulates values, so the
        // assertions above test the counters rather than testing that a mock was called.
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
