package io.github.benhendayoussef.idempotency.behavior;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.benhendayoussef.idempotency.api.Idempotent;
import java.util.Locale;
import java.util.Map;
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
 * A control for the overhead measurement: the in-memory store does zero network I/O, so
 * any overhead measured here is the aspect's own CPU cost (fingerprinting, key composition,
 * reflection, JSON (de)serialization of the record) - not store round-trip latency. Comparing this
 * against {@code AbstractIdempotencyBehaviorTest}'s Redis/JDBC overhead numbers (both ~5-6.4ms
 * p50, each doing two real network round trips per request) isolates how much of that is
 * Testcontainers/Docker network overhead versus the library itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = InMemoryStoreOverheadTest.TestApp.class,
        properties = {"idempotency.store=memory", "idempotency.scope=global",
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE_AND_SECURITY})
@Import(MockMvcTestConfiguration.class)
class InMemoryStoreOverheadTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aspectOverheadWithNoNetworkIo_reportsP50P99() throws Exception {
        int warmup = 20;
        int samples = 200;

        for (int i = 0; i < warmup; i++) {
            mockMvc.perform(post("/mem/plain").contentType("application/json").content("{\"a\":1}"));
            mockMvc.perform(post("/mem/echo").header("Idempotency-Key", "warmup-" + i)
                    .contentType("application/json").content("{\"a\":1}"));
        }

        long[] plainNanos = new long[samples];
        long[] idempotentNanos = new long[samples];
        for (int i = 0; i < samples; i++) {
            long s0 = System.nanoTime();
            mockMvc.perform(post("/mem/plain").contentType("application/json").content("{\"a\":1}"));
            plainNanos[i] = System.nanoTime() - s0;

            long s1 = System.nanoTime();
            mockMvc.perform(post("/mem/echo").header("Idempotency-Key", "k-" + i + "-" + System.nanoTime())
                    .contentType("application/json").content("{\"a\":1}"));
            idempotentNanos[i] = System.nanoTime() - s1;
        }

        java.util.Arrays.sort(plainNanos);
        java.util.Arrays.sort(idempotentNanos);
        double plainP50 = plainNanos[samples / 2] / 1_000_000.0;
        double plainP99 = plainNanos[(int) (samples * 0.99)] / 1_000_000.0;
        double idemP50 = idempotentNanos[samples / 2] / 1_000_000.0;
        double idemP99 = idempotentNanos[(int) (samples * 0.99)] / 1_000_000.0;

        System.out.printf(Locale.US,
                "OVERHEAD-PROBE store=MEMORY(no I/O) plain(p50=%.3fms,p99=%.3fms) idempotent(p50=%.3fms,p99=%.3fms) overhead(p50=%.3fms,p99=%.3fms)%n",
                plainP50, plainP99, idemP50, idemP99, idemP50 - plainP50, idemP99 - plainP99);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        OverheadController overheadController() {
            return new OverheadController();
        }
    }

    @RestController
    static class OverheadController {
        @Idempotent
        @PostMapping("/mem/echo")
        ResponseEntity<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }

        @PostMapping("/mem/plain")
        ResponseEntity<Map<String, Object>> plain(@RequestBody Map<String, Object> body) {
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }
    }
}
