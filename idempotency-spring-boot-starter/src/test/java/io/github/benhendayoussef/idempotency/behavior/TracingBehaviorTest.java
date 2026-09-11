package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.benhendayoussef.idempotency.api.IdempotencyKeys;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What a replay looks like in a trace.
 *
 * <p>Without this, a replayed request is the most confusing span in the system: the endpoint was
 * called, it returned 201, it opened no transaction, issued no query and made no downstream call,
 * and took two milliseconds. That reads as a bug in the handler. The {@code idempotency.outcome}
 * tag is what turns it into an explanation.
 *
 * <p>The tag goes on the request's own span rather than a child of it, so it is visible in a trace
 * list without drilling in - see {@code MicrometerIdempotencyTracer}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {AbstractIdempotencyBehaviorTest.TestApp.class, TracingBehaviorTest.TracingConfig.class},
        properties = {
                "idempotency.store=memory",
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE_AND_SECURITY
        })
@Import(MockMvcTestConfiguration.class)
class TracingBehaviorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SimpleTracer tracer;

    @Autowired
    private AbstractIdempotencyBehaviorTest.MatrixController controller;

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private IdempotencyProperties props;

    @BeforeEach
    void reset() {
        controller.reset();
        tracer.getSpans().clear();
    }

    @Test
    void anExecutedRequestAndItsReplayAreTaggedDifferentlyOnTheRequestSpan() throws Exception {
        String key = "trace-" + System.nanoTime();

        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());
        assertThat(outcomeOfLastSpan()).isEqualTo("executed");

        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());
        assertThat(outcomeOfLastSpan())
                .as("the whole point: a 2ms request that touched nothing is explained, not mysterious")
                .isEqualTo("replayed");

        assertThat(controller.echoCount()).isEqualTo(1);
    }

    /**
     * Tagging is not limited to the happy path. A duplicate rejected with 409 is exactly the kind of
     * response someone opens a trace to explain.
     */
    @Test
    void aRejectedDuplicateIsTaggedAsAConflict() throws Exception {
        String key = "trace-conflict-" + System.nanoTime();

        // A claim held by someone else, exactly as an in-flight first request leaves one.
        store.claim(IdempotencyKeys.storageKey(key, "POST", "/m/fail-fast"), "",
                props.claimTtlFor(props.getDefaultTtl()));

        mockMvc.perform(post("/m/fail-fast").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict());

        assertThat(outcomeOfLastSpan()).isEqualTo("conflict");
        assertThat(controller.failFastCount()).isZero();
    }

    /** The trace records what the library did, not what the handler returned. */
    @Test
    void aRequestWithNoKeyIsTaggedRatherThanLeftUnexplained() throws Exception {
        mockMvc.perform(post("/m/echo").contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());

        assertThat(outcomeOfLastSpan())
                .as("a request that bypassed idempotency entirely should say so")
                .isEqualTo("missing_key");
    }

    private String outcomeOfLastSpan() {
        SimpleSpan span = tracer.getSpans().peekLast();
        assertThat(span).as("the test filter should have opened a span for the request").isNotNull();
        Map<String, String> tags = span.getTags();
        return tags.get("idempotency.outcome");
    }

    /**
     * Stands in for whatever really starts the server span - Boot's own tracing instrumentation in a
     * real application. All the library needs is that some span is current when the aspect runs; this
     * is the smallest thing that makes that true.
     */
    @Configuration(proxyBeanMethods = false)
    static class TracingConfig {

        @Bean
        SimpleTracer simpleTracer() {
            return new SimpleTracer();
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        Filter requestSpanFilter(Tracer tracer) {
            return new Filter() {
                @Override
                public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                        throws IOException, ServletException {
                    var span = tracer.nextSpan().name("http.server").start();
                    try (var ignored = tracer.withSpan(span)) {
                        chain.doFilter(request, response);
                    } finally {
                        span.end();
                    }
                }
            };
        }
    }
}
