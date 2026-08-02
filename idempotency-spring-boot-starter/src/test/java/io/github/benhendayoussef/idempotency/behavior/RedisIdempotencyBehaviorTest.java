package io.github.benhendayoussef.idempotency.behavior;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Runs the full behavioural matrix against a real Redis via Testcontainers. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = AbstractIdempotencyBehaviorTest.TestApp.class,
        // idempotency.scope is left unset, so this runs against the real default (GLOBAL). That
        // makes the whole matrix a regression guard proving replay works with zero idempotency.*
        // properties beyond store selection. The USER-scope case overrides scope per-endpoint via
        // @Idempotent and is unaffected. spring-boot-starter-jdbc and spring-boot-starter-security
        // are also on the test classpath (needed by the JDBC matrix tests and
        // SpringSecurityOrderingTest respectively) - see TestAutoconfigExcludes for why both must
        // be excluded here.
        properties = {"idempotency.store=redis",
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE_AND_SECURITY})
class RedisIdempotencyBehaviorTest extends AbstractIdempotencyBehaviorTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
