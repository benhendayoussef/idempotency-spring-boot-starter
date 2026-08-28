package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.benhendayoussef.idempotency.api.IdempotencyRecord.State;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.internal.IdempotencyKeyComposer;
import io.github.benhendayoussef.idempotency.store.jdbc.internal.IdempotencySqlDialect;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The full behavioural matrix against a real MySQL, plus the dialect-specific cases below.
 *
 * <p>Running the whole inherited suite rather than a handful of smoke tests is the point: the
 * differences between the two dialects are in claim semantics and expiry comparison, which is
 * exactly what the matrix exercises hardest - concurrency, TTL, fingerprint mismatch, the failure
 * policy. A MySQL-specific bug would show up as a wrong answer under one of those, not as a SQL
 * syntax error.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = AbstractIdempotencyBehaviorTest.TestApp.class,
        properties = {
                "idempotency.store=jdbc",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/idempotency/mysql.sql",
                TestAutoconfigExcludes.EXCLUDE_SECURITY
        })
class MySqlIdempotencyBehaviorTest extends AbstractIdempotencyBehaviorTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private IdempotencySqlDialect dialect;

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private IdempotencyKeyComposer composer;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void autoDetectionPicksTheMySqlDialectWithoutBeingTold() throws Exception {
        // idempotency.jdbc.dialect is left at AUTO above, so this asserts detection rather than
        // configuration - the case every MySQL user actually hits.
        //
        // Detection is deferred until the store is first used; that laziness is asserted in
        // IdempotencyAutoConfigurationTest, which can use a fresh context. Here the context is
        // shared with the inherited suite, so the dialect may already be resolved - what this test
        // proves is that detection reached the right answer against a real MySQL.
        mockMvc.perform(post("/m/echo").header("Idempotency-Key", "detect-" + System.nanoTime())
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());

        assertThat(dialect.name()).isEqualTo("MySQL");
    }

    /**
     * The reclaim path, which is where the two dialects diverge most. Postgres expresses it as
     * {@code ON CONFLICT ... WHERE expired}; MySQL has to push the condition into every assignment,
     * and the update reports 2 affected rows rather than 1.
     *
     * <p>A dialect that got the affected-row reading wrong would fail here specifically: the caller
     * would be told the key is already held by a row it just wrote itself, and get a 409 instead of
     * executing.
     */
    @Test
    void anExpiredRowIsReclaimedByTheNextClaimRatherThanConflicting() throws Exception {
        String key = "mysql-reclaim-" + System.nanoTime();
        String storageKey = storageKeyFor(key);

        var first = store.claim(storageKey, "fp", Duration.ofMillis(200));
        assertThat(first).isInstanceOf(IdempotencyStore.ClaimResult.Acquired.class);

        Thread.sleep(400);

        var second = store.claim(storageKey, "fp", Duration.ofSeconds(30));
        assertThat(second)
                .as("the row had expired, so this claim must reclaim it - not report a conflict")
                .isInstanceOf(IdempotencyStore.ClaimResult.Acquired.class);

        assertThat(store.find(storageKey)).isPresent().get()
                .extracting(io.github.benhendayoussef.idempotency.api.IdempotencyRecord::state)
                .isEqualTo(State.IN_PROGRESS);
    }

    /**
     * The other half of the same statement: a <em>live</em> row must be left completely untouched.
     * MySQL applies the assignments left to right, so a guard ordering mistake would half-apply the
     * reclaim here - wiping the stored payload of a record someone is about to replay - while still
     * reporting a conflict, which no status-code assertion would catch.
     */
    @Test
    void aLiveRecordIsNotModifiedByACompetingClaim() throws Exception {
        String key = "mysql-live-" + System.nanoTime();
        String storageKey = storageKeyFor(key);

        mockMvc.perform(post("/m/echo").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"a\":1}"))
                .andExpect(status().isCreated());

        var before = store.find(storageKey).orElseThrow();
        assertThat(before.state()).isEqualTo(State.COMPLETED);

        // A competing claim on the same live key: must change nothing at all.
        store.claim(storageKey, "different-fingerprint", Duration.ofSeconds(30));

        var after = store.find(storageKey).orElseThrow();
        assertThat(after.state()).as("a live COMPLETED record must survive a competing claim")
                .isEqualTo(State.COMPLETED);
        assertThat(after.fingerprint()).isEqualTo(before.fingerprint());
        assertThat(after.payload()).isEqualTo(before.payload());
        assertThat(after.status()).isEqualTo(before.status());
    }

    private String storageKeyFor(String clientKey) {
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/m/echo");
        request.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/m/echo");
        return composer.compose(clientKey, "", request);
    }
}
