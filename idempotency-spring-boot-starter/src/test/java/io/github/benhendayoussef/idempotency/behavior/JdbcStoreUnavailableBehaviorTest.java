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
 * Rows 14/15 for the JDBC store: a {@code DataSource} pointed at a port nothing listens on,
 * configured with a short connection timeout so the test fails fast instead of hanging on the
 * driver's default connect timeout. Isolated into its own context for the same reason as
 * {@link RedisStoreUnavailableBehaviorTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = JdbcStoreUnavailableBehaviorTest.UnavailableTestApp.class,
        properties = {
                "idempotency.store=jdbc",
                "idempotency.scope=global", // avoid needing an authenticated principal for this test
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/nope?connectTimeout=1&socketTimeout=1&loginTimeout=1",
                "spring.datasource.username=nope",
                "spring.datasource.password=nope",
                "spring.datasource.hikari.connection-timeout=1000",
                "spring.datasource.hikari.initialization-fail-timeout=-1",
                // spring-boot-starter-security is also on the test classpath (needed by
                // SpringSecurityOrderingTest) - see TestAutoconfigExcludes.
                TestAutoconfigExcludes.EXCLUDE_SECURITY
        })
@Import(MockMvcTestConfiguration.class)
class JdbcStoreUnavailableBehaviorTest {

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

    // See RedisStoreUnavailableBehaviorTest for why EchoController/CountingMetrics must not be
    // nested inside this @Configuration class.
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

        int count() {
            return count.get();
        }

        @Idempotent
        @PostMapping("/u/echo")
        ResponseEntity<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
            count.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }
    }
}
