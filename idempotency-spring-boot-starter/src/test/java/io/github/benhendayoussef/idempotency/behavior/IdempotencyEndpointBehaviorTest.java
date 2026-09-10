package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.benhendayoussef.idempotency.api.IdempotencyKeys;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The operator's remedy for a key stuck {@code IN_PROGRESS}, exercised over real HTTP.
 *
 * <p>The scenario these cover is the one that had no answer before 0.4: a process died mid-request,
 * its claim is still held, and every retry is getting a 409. Inspecting tells you that is what
 * happened; evicting lets the customer retry now rather than waiting out the lease.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = AbstractIdempotencyBehaviorTest.TestApp.class,
        properties = {
                "idempotency.store=memory",
                "idempotency.on-conflict=fail_fast",
                // Actuator hides everything but health/info by default - that default is why this
                // endpoint is not a surprise, so the test has to opt in the same way an operator does.
                "management.endpoints.web.exposure.include=idempotency",
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE_SECURITY_AND_MANAGEMENT
        })
@Import(MockMvcTestConfiguration.class)
class IdempotencyEndpointBehaviorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private IdempotencyProperties props;

    @Autowired
    private AbstractIdempotencyBehaviorTest.MatrixController controller;

    @BeforeEach
    void reset() {
        controller.reset();
    }

    @Test
    void aCompletedRecordCanBeInspectedWithoutExposingTheResponseBody() throws Exception {
        String key = "inspect-" + System.nanoTime();

        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/actuator/idempotency")
                        .param("key", key).param("method", "POST").param("route", "/m/echo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.payloadBytes").isNumber())
                // The stored body is the application's own response - often customer data. The
                // endpoint reports its size and nothing more.
                .andExpect(jsonPath("$.payload").doesNotExist());
    }

    /** The whole reason the endpoint exists. */
    @Test
    void aStuckClaimCanBeEvictedSoTheCustomerCanRetryImmediately() throws Exception {
        String key = "stuck-" + System.nanoTime();

        // Exactly what a process that died mid-request leaves behind.
        store.claim(IdempotencyKeys.storageKey(key, "POST", "/m/echo"), "",
                props.claimTtlFor(props.getDefaultTtl()));

        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/actuator/idempotency")
                        .param("key", key).param("method", "POST").param("route", "/m/echo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evicted").value(true));

        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());
        assertThat(controller.echoCount())
                .as("after eviction the retry must actually execute")
                .isEqualTo(1);
    }

    /**
     * Getting the route pattern wrong produces a valid-looking key that addresses nothing. Saying
     * "evicted nothing" beats reporting a success, because the operator would otherwise walk away
     * believing they had fixed it.
     */
    @Test
    void evictingSomethingThatIsNotThereSaysSoRatherThanClaimingSuccess() throws Exception {
        mockMvc.perform(delete("/actuator/idempotency")
                        .param("key", "never-existed").param("method", "POST").param("route", "/m/echo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evicted").value(false))
                .andExpect(jsonPath("$.note").exists());
    }

    @Test
    void inspectingAnUnknownKeyReportsNotFoundRatherThanFailing() throws Exception {
        mockMvc.perform(get("/actuator/idempotency")
                        .param("key", "unknown-" + System.nanoTime())
                        .param("method", "POST").param("route", "/m/echo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.storageKey").exists());
    }

    @Test
    void aScopedRecordIsAddressedByPassingItsNamespace() throws Exception {
        String key = "scoped-" + System.nanoTime();
        store.claim(IdempotencyKeys.storageKey(key, "POST", "/m/echo", "alice"), "",
                props.claimTtlFor(props.getDefaultTtl()));

        // Without the namespace it is a different key entirely, and must not be found.
        mockMvc.perform(get("/actuator/idempotency")
                        .param("key", key).param("method", "POST").param("route", "/m/echo"))
                .andExpect(jsonPath("$.found").value(false));

        mockMvc.perform(get("/actuator/idempotency")
                        .param("key", key).param("method", "POST").param("route", "/m/echo")
                        .param("namespace", "alice"))
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.state").value("IN_PROGRESS"));
    }
}
