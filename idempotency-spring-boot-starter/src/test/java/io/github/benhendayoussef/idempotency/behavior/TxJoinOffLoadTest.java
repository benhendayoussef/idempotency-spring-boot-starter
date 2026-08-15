package io.github.benhendayoussef.idempotency.behavior;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The control arm: identical to {@link TxJoinOnLoadTest} in every respect except that
 * {@code join-transaction} is left at its default. The difference between the two reported lines is
 * the cost of the property and nothing else. See {@link AbstractTxJoinLoadTest}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AbstractTxJoinLoadTest.TestApp.class,
        properties = {
                "idempotency.store=jdbc",
                // join-transaction deliberately unset - this is the default-mode control.
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/idempotency/postgres.sql",
                "spring.datasource.hikari.maximum-pool-size=4",
                "spring.datasource.hikari.connection-timeout=10000",
                "server.tomcat.threads.max=32",
                TestAutoconfigExcludes.EXCLUDE_SECURITY
        })
class TxJoinOffLoadTest extends AbstractTxJoinLoadTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Override
    String modeLabel() {
        return "default";
    }
}
