package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.benhendayoussef.idempotency.api.IdempotencyKeys;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.UnexpectedRollbackException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code idempotency.jdbc.on-silent-rollback=fail}: the other answer to the question
 * {@link JdbcTxJoinBehaviorTest}'s row 6 leaves open.
 *
 * <p>A handler that writes a row, calls {@code setRollbackOnly()} and then returns 201 has made two
 * contradictory statements. The default, {@code RETURN_RESPONSE}, honours the returned one - it is
 * what every release through 0.4 did, and row 6 pins it. This setting honours the other: a 201
 * describing a row that was never committed is a lie to the caller, so the caller gets a failure
 * instead and can retry.
 *
 * <p>What must <em>not</em> differ between the two is the claim: nothing committed either way, so
 * under both policies the key is released and immediately reusable. Only the response changes.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {AbstractIdempotencyBehaviorTest.TestApp.class,
                JdbcTxJoinBehaviorTest.TxJoinController.class},
        properties = {
                "idempotency.store=jdbc",
                "idempotency.jdbc.join-transaction=true",
                "idempotency.jdbc.on-silent-rollback=fail",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/idempotency/postgres.sql,classpath:db/txjoin/business.sql",
                TestAutoconfigExcludes.EXCLUDE_SECURITY
        })
@Import(MockMvcTestConfiguration.class)
class SilentRollbackPolicyTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcTxJoinBehaviorTest.TxJoinController controller;

    /** The same request row 6 answers with 201. */
    @Test
    void aHandlerThatRollsBackAndReturnsCreatedIsReportedAsAFailureInstead() throws Exception {
        String key = "silent-fail-" + System.nanoTime();
        int before = controller.rollbackOnlyCount();

        postExpectingRollbackToSurface(key);

        assertThat(controller.rollbackOnlyCount()).isEqualTo(before + 1);
        assertThat(businessRowCount(key))
                .as("the row the 201 would have described does not exist - that is the point")
                .isZero();
    }

    /**
     * Failing the request must not also strand the key. If it did, this setting would trade a
     * misleading 201 for a 409 on every retry until the lease expired, which is worse than what it
     * replaced.
     */
    @Test
    void theKeyIsStillReleasedSoTheCallerCanRetryImmediately() throws Exception {
        String key = "silent-fail-retry-" + System.nanoTime();

        postExpectingRollbackToSurface(key);

        assertThat(store.find(IdempotencyKeys.storageKey(key, "POST", "/m/tx-rollback-only")))
                .as("nothing committed, so nothing may be left claimed or replayable")
                .isEmpty();

        // Reusing the key must reach the handler again rather than colliding with an orphaned claim.
        // That it fails a second time is the handler's doing; what matters is that it ran at all.
        int before = controller.rollbackOnlyCount();
        postExpectingRollbackToSurface(key);
        assertThat(controller.rollbackOnlyCount())
                .as("a 409 here would mean the failed request stranded its key")
                .isEqualTo(before + 1);
    }

    /**
     * The rollback has to reach the caller as a failure, one way or another. MockMvc rethrows what no
     * resolver handles instead of rendering it, so this accepts either shape - a thrown
     * {@link UnexpectedRollbackException} or a 5xx - and rejects the one thing the setting exists to
     * prevent: a 2xx.
     */
    private void postExpectingRollbackToSurface(String key) throws Exception {
        try {
            int status = mockMvc.perform(post("/m/tx-rollback-only").header("Idempotency-Key", key)
                            .contentType("application/json").content("{\"id\":\"" + key + "\"}"))
                    .andReturn().getResponse().getStatus();
            assertThat(status)
                    .as("on-silent-rollback=fail must not report success for an uncommitted write")
                    .isGreaterThanOrEqualTo(500);
        } catch (Exception thrown) {
            if (!chainContainsRollback(thrown)) {
                fail("expected the rollback to surface, but got: " + thrown, thrown);
            }
        }
    }

    private static boolean chainContainsRollback(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnexpectedRollbackException) {
                return true;
            }
        }
        return false;
    }

    private int businessRowCount(String id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM tx_join_business WHERE id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }
}
