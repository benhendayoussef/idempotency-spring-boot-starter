package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.benhendayoussef.idempotency.api.Idempotent;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A whole request, in an application with <strong>no Spring Security on the classpath at all</strong>.
 *
 * <p>Spring Security is {@code compileOnly} for {@code idempotency-core}, so every reference to it
 * has to sit on a path a {@code scope=global} application never reaches. Get that wrong and the
 * failure is a {@code NoClassDefFoundError} on the first request rather than at startup - which
 * passes every context test in this suite and breaks in production.
 *
 * <p>This runs only under the {@code testNoSpringSecurity} Gradle task, which strips the Spring
 * Security jars from the test runtime classpath. It is excluded from the normal {@code test} task
 * because there it would prove nothing: the classes would be present and the assertions would pass
 * whether or not the library touched them.
 *
 * <p>An earlier attempt at this used {@code FilteredClassLoader} inside the ordinary suite and was
 * <em>worthless</em>: it passed, and it went on passing after the aspect was deliberately changed to
 * call into Spring Security on every request. {@code FilteredClassLoader} gates the name lookups
 * that drive {@code @ConditionalOnClass}; it does not stop runtime linkage in classes the
 * application class loader has already defined. Only a genuinely smaller classpath tests this, which
 * is why this needs its own task and its own JVM. Hence {@link #springSecurityIsGenuinelyAbsent()} -
 * if the classpath filter is ever removed or renamed, that test fails loudly instead of letting this
 * whole class go quietly vacuous again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {NoSpringSecurityRequestTest.App.class, NoSpringSecurityRequestTest.Orders.class},
        properties = {
                "idempotency.store=memory",
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE_AND_SECURITY
        })
@Import(MockMvcTestConfiguration.class)
class NoSpringSecurityRequestTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Orders orders;

    // The context - and so the controller singleton - is shared by every test in this class.
    // Without this the execution count carries over between them, which is how the first version
    // of this test managed to report a working replay as a failure.
    @BeforeEach
    void resetCounter() {
        orders.reset();
    }

    /**
     * Guards every other assertion in this class. Without it, a change to the Gradle task's
     * classpath filter would turn this suite into a second copy of the ordinary in-memory tests
     * that still reports green.
     */
    @Test
    void springSecurityIsGenuinelyAbsent() {
        assertThatThrownBy(() -> Class.forName("org.springframework.security.core.context.SecurityContextHolder"))
                .as("the testNoSpringSecurity task must strip Spring Security, or this class proves nothing")
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void aGlobalScopedRequestExecutesOnceAndThenReplays() throws Exception {
        String key = "no-security-" + System.nanoTime();

        mockMvc.perform(post("/ns/orders").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"amount\":10}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/ns/orders").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"amount\":10}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"));

        assertThat(orders.count())
                .as("the handler must run exactly once - this has to actually work here, "
                        + "not merely avoid throwing NoClassDefFoundError")
                .isEqualTo(1);
    }

    /**
     * The conflict path resolves a namespace too, and takes a different route through the aspect
     * than the happy path does. Worth covering separately: a Security reference added to error
     * handling would be missed by the test above.
     */
    @Test
    void aFingerprintMismatchIsStillRejectedWithoutSecurityPresent() throws Exception {
        String key = "no-security-mismatch-" + System.nanoTime();

        mockMvc.perform(post("/ns/orders").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"amount\":10}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/ns/orders").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"amount\":999}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @SpringBootApplication
    static class App {
    }

    @RestController
    static class Orders {

        private final AtomicInteger calls = new AtomicInteger();

        @Idempotent
        @PostMapping("/ns/orders")
        ResponseEntity<Map<String, Object>> place(@RequestBody Map<String, Object> body) {
            calls.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true));
        }

        int count() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }
    }
}
