package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.benhendayoussef.idempotency.api.Idempotent;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties;
import io.github.benhendayoussef.idempotency.api.IdempotencyMetrics;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rows 14/15: store unreachable. Points {@code idempotency.store=redis} at a port nothing is
 * listening on (rather than starting-then-stopping a Testcontainers instance, which would
 * corrupt any test running after it in the same class) - a real connection failure, not a mock.
 * Isolated into its own class/context so a permanently-broken store can't affect any other test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = RedisStoreUnavailableBehaviorTest.UnavailableTestApp.class,
        properties = {
                "idempotency.store=redis",
                "idempotency.scope=global", // avoid needing an authenticated principal for this test
                "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=1", // nothing listens on port 1; connection is refused fast
                "spring.data.redis.timeout=1s",
                "spring.data.redis.connect-timeout=1s",
                // spring-boot-starter-jdbc/-security are also on the test classpath (needed by
                // other tests in this module) - see TestAutoconfigExcludes.
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE_AND_SECURITY
        })
@Import(MockMvcTestConfiguration.class)
class RedisStoreUnavailableBehaviorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EchoController controller;

    @Autowired
    private CountingMetrics metrics;

    @Autowired
    private IdempotencyProperties props;

    @Test
    void row14_storeUnreachableOnStoreFailureProceed_requestSucceedsAndMetricIncremented() throws Exception {
        controller.reset();
        metrics.storeFailureCount.set(0);
        props.setOnStoreFailure(IdempotencyProperties.OnStoreFailure.PROCEED);

        mockMvc.perform(post("/u/echo")
                        .header("Idempotency-Key", "k1")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated());

        assertThat(controller.count()).isEqualTo(1);
        assertThat(metrics.storeFailureCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void row15_storeUnreachableOnStoreFailureFail_returns503RequestNotExecuted() throws Exception {
        controller.reset();
        props.setOnStoreFailure(IdempotencyProperties.OnStoreFailure.FAIL);
        try {
            mockMvc.perform(post("/u/echo")
                            .header("Idempotency-Key", "k2")
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isServiceUnavailable());

            assertThat(controller.count()).isZero();
        } finally {
            props.setOnStoreFailure(IdempotencyProperties.OnStoreFailure.PROCEED);
        }
    }

    // Not @SpringBootApplication (no @ComponentScan) and EchoController/CountingMetrics are NOT
    // nested inside this @Configuration class: Spring's ConfigurationClassParser auto-registers
    // any @Component-stereotyped member class of a @Configuration class regardless of
    // @ComponentScan, which previously produced a duplicate bean and an "ambiguous mapping"
    // failure alongside the explicit @Bean methods below.
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class UnavailableTestApp {

        @Bean
        EchoController echoController() {
            return new EchoController();
        }

        @Bean
        CountingMetrics countingMetrics() {
            return new CountingMetrics();
        }
    }

    static class CountingMetrics implements IdempotencyMetrics {
        final AtomicInteger storeFailureCount = new AtomicInteger();

        @Override
        public void storeFailure() {
            storeFailureCount.incrementAndGet();
        }
    }

    @RestController
    static class EchoController {
        final AtomicInteger count = new AtomicInteger();

        void reset() {
            count.set(0);
        }

        // echo() is @Idempotent, so this bean is CGLIB-proxied; direct field access on the
        // @Autowired reference would read the proxy's own uninitialized field copy, not the real
        // target's - go through a method instead (see AbstractIdempotencyBehaviorTest for detail).
        int count() {
            return count.get();
        }

        // idempotency.on-store-failure has no per-endpoint @Idempotent override, so each test
        // method sets it directly on the (mutable, singleton) IdempotencyProperties bean.
        @Idempotent
        @PostMapping("/u/echo")
        ResponseEntity<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
            count.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }
    }
}
