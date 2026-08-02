package io.github.benhendayoussef.idempotency.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.benhendayoussef.idempotency.api.Idempotent;
import io.github.benhendayoussef.idempotency.api.IdempotencyMetrics;
import io.github.benhendayoussef.idempotency.api.IdempotencyScope;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.api.ScopeResolver;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties;
import io.github.benhendayoussef.idempotency.internal.scope.GlobalScopeResolver;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * The Step 4 checkpoint: an in-memory store plus a MockMvc test showing the second identical
 * POST returns the first response, not a re-execution.
 */
@SpringJUnitWebConfig(classes = IdempotencyAspectMockMvcTest.TestConfig.class)
class IdempotencyAspectMockMvcTest {

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private TestController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        controller.reset();
    }

    @Test
    void duplicateRequestReplaysTheFirstResponseInsteadOfReExecuting() throws Exception {
        mockMvc.perform(post("/echo")
                        .header("Idempotency-Key", "order-1")
                        .contentType("application/json")
                        .content("{\"amount\":10}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/echo")
                        .header("Idempotency-Key", "order-1")
                        .contentType("application/json")
                        .content("{\"amount\":10}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"));

        assertThat(controller.getEchoCount()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyReturns422AndDoesNotReExecute() throws Exception {
        mockMvc.perform(post("/echo")
                        .header("Idempotency-Key", "order-2")
                        .contentType("application/json")
                        .content("{\"amount\":10}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/echo")
                        .header("Idempotency-Key", "order-2")
                        .contentType("application/json")
                        .content("{\"amount\":999}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(controller.getEchoCount()).isEqualTo(1);
    }

    @Test
    void handlerThrowing5xxReleasesTheKeySoARetryReExecutes() throws Exception {
        mockMvc.perform(post("/fail5xx")
                        .header("Idempotency-Key", "order-3")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isInternalServerError());

        mockMvc.perform(post("/fail5xx")
                        .header("Idempotency-Key", "order-3")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isInternalServerError());

        assertThat(controller.getFail5xxCount()).isEqualTo(2);
    }

    @Test
    void handlerThrowing4xxKeepsTheKeySoARetryReplaysTheSame4xx() throws Exception {
        mockMvc.perform(post("/fail4xx")
                        .header("Idempotency-Key", "order-4")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/fail4xx")
                        .header("Idempotency-Key", "order-4")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(controller.getFail4xxCount()).isEqualTo(1);
    }

    @Test
    void spelKeyDerivedFromTheRealParameterNameDrivesReplay() throws Exception {
        // Proof that -parameters is actually applied: #orderId only resolves if the compiler
        // emitted the real parameter name, not a synthetic arg0/arg1.
        mockMvc.perform(post("/spel/order-42")
                        .contentType("application/json")
                        .content("{\"amount\":10}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/spel/order-42")
                        .contentType("application/json")
                        .content("{\"amount\":10}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"));

        assertThat(controller.getSpelCount()).isEqualTo(1);
    }

    @Test
    void selfInvocationBypassesTheProxyBothCallsExecute() throws Exception {
        // /self-invoke's handler is NOT @Idempotent; it calls this.selfInvokedIdempotent()
        // directly. That inner method IS @Idempotent, but a plain "this." call from within the
        // target object never passes through the AOP proxy, so the aspect never runs for it -
        // both calls execute, even with the same key. This is the same, accepted limitation as
        // @Transactional's self-invocation gap; it is not something this library can fix by
        // itself, only document (see IdempotentMethodVisibilityValidator for the startup-time
        // WARN this library adds for the related "non-public method" case).
        String key = "self-invoke-key";
        mockMvc.perform(post("/self-invoke").header("Idempotency-Key", key))
                .andExpect(status().isOk());
        mockMvc.perform(post("/self-invoke").header("Idempotency-Key", key))
                .andExpect(status().isOk());

        assertThat(controller.getSelfInvokedCount()).isEqualTo(2);
    }

    @Test
    void callingTheSameMethodThroughTheProxyExternallyDoesReplay() throws Exception {
        // Contrast with the test above: the exact same annotated method, called the normal way
        // (as the externally-dispatched handler, through the proxy), replays correctly.
        String key = "direct-invoke-key";
        mockMvc.perform(post("/self-invoke-direct").header("Idempotency-Key", key))
                .andExpect(status().isOk());
        mockMvc.perform(post("/self-invoke-direct").header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));

        assertThat(controller.getSelfInvokedCount()).isEqualTo(1);
    }

    @Test
    void missingKeyPassesThroughWhenNotRequired() throws Exception {
        mockMvc.perform(post("/echo").contentType("application/json").content("{\"amount\":1}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/echo").contentType("application/json").content("{\"amount\":1}"))
                .andExpect(status().isCreated());

        assertThat(controller.getEchoCount()).isEqualTo(2);
    }

    @RestController
    static class TestController {

        final AtomicInteger echoCount = new AtomicInteger();
        final AtomicInteger fail5xxCount = new AtomicInteger();
        final AtomicInteger fail4xxCount = new AtomicInteger();
        final AtomicInteger spelCount = new AtomicInteger();

        @Idempotent
        @PostMapping("/echo")
        public ResponseEntity<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
            echoCount.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }

        @Idempotent
        @PostMapping("/fail5xx")
        public String fail5xx(@RequestBody Map<String, Object> body) {
            fail5xxCount.incrementAndGet();
            throw new InfrastructureException();
        }

        @Idempotent
        @PostMapping("/fail4xx")
        public String fail4xx(@RequestBody Map<String, Object> body) {
            fail4xxCount.incrementAndGet();
            throw new ClientException();
        }

        @Idempotent(key = "#orderId")
        @PostMapping("/spel/{orderId}")
        public ResponseEntity<Map<String, Object>> spelKeyed(
                @PathVariable String orderId, @RequestBody Map<String, Object> body) {
            spelCount.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }

        // Plain methods, called directly (not via mockMvc): reads must go through the AOP proxy's
        // target dispatch, not direct field access, or they'd read the proxy's own uninitialized copy.
        int getEchoCount() {
            return echoCount.get();
        }

        int getFail5xxCount() {
            return fail5xxCount.get();
        }

        int getFail4xxCount() {
            return fail4xxCount.get();
        }

        int getSpelCount() {
            return spelCount.get();
        }

        void reset() {
            echoCount.set(0);
            fail5xxCount.set(0);
            fail4xxCount.set(0);
            spelCount.set(0);
            selfInvokedCount.set(0);
        }

        // --- Self-invocation: the well-known AOP limitation, same as @Transactional -----------

        final AtomicInteger selfInvokedCount = new AtomicInteger();

        // Not @Idempotent itself, so the aspect never sees this call at the proxy layer.
        @PostMapping("/self-invoke")
        public ResponseEntity<String> selfInvokeOuter(@RequestBody(required = false) String body) {
            return this.selfInvokedIdempotent(); // plain "this." call - bypasses the proxy entirely
        }

        @Idempotent
        @PostMapping("/self-invoke-direct")
        public ResponseEntity<String> selfInvokedIdempotent() {
            selfInvokedCount.incrementAndGet();
            return ResponseEntity.ok("invoked");
        }

        int getSelfInvokedCount() {
            return selfInvokedCount.get();
        }
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    static class InfrastructureException extends RuntimeException {
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    static class ClientException extends RuntimeException {
    }

    @EnableWebMvc
    @Configuration
    @org.springframework.context.annotation.EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        IdempotencyStore idempotencyStore() {
            return new InMemoryIdempotencyStore();
        }

        @Bean
        IdempotencyProperties idempotencyProperties() {
            return new IdempotencyProperties();
        }

        @Bean
        ArgumentFingerprinter argumentFingerprinter() {
            return new ArgumentFingerprinter();
        }

        @Bean
        IdempotencyKeyComposer idempotencyKeyComposer() {
            return new IdempotencyKeyComposer();
        }

        @Bean
        Map<IdempotencyScope, ScopeResolver> scopeResolvers() {
            return Map.of(IdempotencyScope.GLOBAL, new GlobalScopeResolver());
        }

        @Bean
        ObjectMapper payloadMapper() {
            return JsonMapper.builder().addModule(new JavaTimeModule()).build();
        }

        @Bean
        IdempotencyMetrics idempotencyMetrics() {
            return new NoOpIdempotencyMetrics();
        }

        @Bean
        IdempotencyAspect idempotencyAspect(IdempotencyStore store, IdempotencyProperties props,
                ArgumentFingerprinter fingerprinter, IdempotencyKeyComposer composer,
                Map<IdempotencyScope, ScopeResolver> scopes, ObjectMapper payloadMapper,
                IdempotencyMetrics metrics) {
            props.setScope(IdempotencyScope.GLOBAL);
            return new IdempotencyAspect(store, props, fingerprinter, composer, scopes, payloadMapper, metrics);
        }

        @Bean
        IdempotencyExceptionHandler idempotencyExceptionHandler(IdempotencyProperties props) {
            return new IdempotencyExceptionHandler(props);
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }
}
