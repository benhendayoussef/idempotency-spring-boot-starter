package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.benhendayoussef.idempotency.api.Idempotent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Confirms the aspect works correctly when the request is handled on a virtual thread.
 * Deliberately uses a real embedded server ({@code webEnvironment = RANDOM_PORT}) with a plain JDK
 * {@link HttpClient}, not MockMvc: MockMvc invokes the DispatcherServlet in-process on the calling
 * test thread and never goes through Tomcat's own connector/executor, so it cannot exercise
 * {@code spring.threads.virtual.enabled} at all - the property only affects which executor Tomcat
 * uses to run its own request-handling threads. A plain {@code HttpClient} is used instead of a
 * Spring test REST client to avoid depending on exactly which Boot module currently ships one
 * (churned across recent Boot 4 releases) - this test only needs a real socket round trip.
 *
 * <p>{@code Thread.isVirtual()} is a JDK 21+ API; this module's toolchain is pinned to Java 17, so
 * it is called reflectively rather than referenced directly, which would fail to compile at the 17
 * language level even on a JDK 21+ runtime.
 *
 * <p>Runs on a dedicated Gradle task pinned to a JDK 21+ launcher (see the
 * {@code testVirtualThreads} task in this module's {@code build.gradle.kts}), separate from the
 * project's normal Java 17 toolchain: virtual threads don't exist at all on 17, so this check is
 * only meaningful on a newer runtime, without changing what JDK the library itself is built/tested
 * against.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = VirtualThreadsTest.TestApp.class,
        properties = {"idempotency.store=memory", "idempotency.scope=global", "spring.threads.virtual.enabled=true",
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE_AND_SECURITY})
class VirtualThreadsTest {

    @LocalServerPort
    private int port;

    @Test
    void idempotentEndpointWorksCorrectlyOnAVirtualThreadAndStillReplays() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/vt"))
                .header("Idempotency-Key", "vt-key")
                .GET()
                .build();

        HttpResponse<String> first = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(first.statusCode()).isEqualTo(200);
        // Confirms the test setup is genuinely exercising a virtual thread, not silently running
        // on a platform thread because the runtime JDK or the property didn't take effect - if
        // this is ever "false", the rest of the assertions would be proving nothing.
        assertThat(first.body()).as("handler must actually run on a virtual thread").isEqualTo("true");

        HttpResponse<String> second = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.headers().firstValue("Idempotent-Replay")).hasValue("true");
        assertThat(second.body()).isEqualTo(first.body());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        VirtualThreadController virtualThreadController() {
            return new VirtualThreadController();
        }
    }

    @RestController
    static class VirtualThreadController {
        final AtomicInteger count = new AtomicInteger();

        @Idempotent
        @GetMapping("/vt")
        ResponseEntity<String> handle() {
            count.incrementAndGet();
            return ResponseEntity.ok(String.valueOf(isCurrentThreadVirtual()));
        }

        private static boolean isCurrentThreadVirtual() {
            try {
                return (boolean) Thread.class.getMethod("isVirtual").invoke(Thread.currentThread());
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }
    }
}
