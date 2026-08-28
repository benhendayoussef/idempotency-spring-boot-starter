package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.benhendayoussef.idempotency.api.Idempotent;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The store-unreachable tests use a <em>closed</em> port, which fails instantly - not the
 * production failure mode of a store that accepts a connection and then simply never responds (a
 * wedged Redis, a network partition that drops responses but not the TCP handshake, and so on). A
 * plain {@link ServerSocket} that accepts but never writes back stands in for that, without
 * needing an external Toxiproxy dependency.
 *
 * <p>This is an <strong>investigative</strong> test: it asserts only that the request completes
 * within a generous bound and reports the actual elapsed time, because the observed latency does
 * not necessarily track the configured client timeout. See the README's limitations section.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = SlowStoreBehaviorTest.SlowStoreTestApp.class,
        properties = {
                "idempotency.store=redis",
                "idempotency.on-store-failure=proceed",
                // Lettuce's command timeout - how long a single command (e.g. SETNX) waits for a
                // response before giving up. The connection itself succeeds instantly (the fake
                // server does accept() the socket); it is the command response that never arrives.
                "spring.data.redis.timeout=1s",
                "spring.data.redis.connect-timeout=1s",
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE_AND_SECURITY
        })
@Import(MockMvcTestConfiguration.class)
class SlowStoreBehaviorTest {

    private static ServerSocket wedgedServer;
    private static volatile boolean stop;
    private static Thread acceptorThread;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EchoController controller;

    @AfterAll
    static void stopWedgedServer() throws IOException {
        stop = true;
        wedgedServer.close();
    }

    @DynamicPropertySource
    static void wedgedServerProperties(DynamicPropertyRegistry registry) throws IOException {
        wedgedServer = new ServerSocket(0);
        acceptorThread = new Thread(() -> {
            while (!stop) {
                try {
                    Socket socket = wedgedServer.accept();
                    // Accept the TCP connection (so this is not a connection-refused/closed-port
                    // scenario, which the store-unreachable tests already cover) but never read or
                    // write anything - the client's command sits there until its own timeout gives up.
                } catch (IOException e) {
                    // Expected on shutdown when the server socket is closed.
                }
            }
        }, "wedged-redis-acceptor");
        acceptorThread.setDaemon(true);
        acceptorThread.start();

        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", wedgedServer::getLocalPort);
    }

    @Test
    void slowButAliveStore_onStoreFailureProceed_requestCompletesWithinABoundedTime() throws Exception {
        controller.reset();
        long start = System.nanoTime();

        mockMvc.perform(post("/m/echo")
                        .header("Idempotency-Key", "slow-store-key")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated());

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("%nSlow-store result: request completed in %dms against a 1s Lettuce "
                + "command timeout (on-store-failure=proceed).%n", elapsedMillis);

        assertThat(controller.count()).isEqualTo(1);
        // Generous bound (10x the configured 1s command timeout) - the point is proving there IS
        // a bound at all (driven by the consumer-configured spring.data.redis.timeout), not pinning
        // an exact number. A hang past this would itself be the finding.
        assertThat(elapsedMillis).as("request must complete within a bounded time, not hang")
                .isLessThan(10_000);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class SlowStoreTestApp {
        @Bean
        EchoController echoController() {
            return new EchoController();
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
        @PostMapping("/m/echo")
        ResponseEntity<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
            count.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }
    }
}
