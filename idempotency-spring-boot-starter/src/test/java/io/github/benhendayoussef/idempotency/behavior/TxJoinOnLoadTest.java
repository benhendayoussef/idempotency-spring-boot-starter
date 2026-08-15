package io.github.benhendayoussef.idempotency.behavior;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The measured arm: {@code join-transaction=true}. See {@link AbstractTxJoinLoadTest}. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AbstractTxJoinLoadTest.TestApp.class,
        properties = {
                "idempotency.store=jdbc",
                "idempotency.jdbc.join-transaction=true",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/idempotency/postgres.sql",
                // Smaller than the request concurrency so the ceiling is reachable in seconds.
                "spring.datasource.hikari.maximum-pool-size=4",
                // Bounded, so exhaustion surfaces as a failed request rather than a hung test.
                "spring.datasource.hikari.connection-timeout=10000",
                // Comfortably above the request concurrency: the constraint under test is the
                // connection pool, not the servlet thread pool.
                "server.tomcat.threads.max=32",
                TestAutoconfigExcludes.EXCLUDE_SECURITY
        })
class TxJoinOnLoadTest extends AbstractTxJoinLoadTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Override
    String modeLabel() {
        return "joined";
    }
}
