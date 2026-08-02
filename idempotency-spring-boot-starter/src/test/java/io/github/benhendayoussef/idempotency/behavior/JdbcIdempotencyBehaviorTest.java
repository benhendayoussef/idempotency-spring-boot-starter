package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import io.github.benhendayoussef.idempotency.api.Idempotent;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord.State;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the full behavioural matrix, plus the JDBC-specific transactional cases below, against a
 * real Postgres via Testcontainers.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {AbstractIdempotencyBehaviorTest.TestApp.class, JdbcIdempotencyBehaviorTest.TxRollbackController.class},
        properties = {
                "idempotency.store=jdbc",
                // idempotency.scope is left unset so this runs against the real default (GLOBAL).
                // See RedisIdempotencyBehaviorTest for why that matters.
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/idempotency/postgres.sql",
                // spring-boot-starter-security is also on the test classpath (needed by
                // SpringSecurityOrderingTest) - see TestAutoconfigExcludes.
                TestAutoconfigExcludes.EXCLUDE_SECURITY
        })
class JdbcIdempotencyBehaviorTest extends AbstractIdempotencyBehaviorTest {

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
    private TxRollbackController txController;

    @Autowired
    private IdempotencyStore store;

    /**
     * Characterisation test pinning the <strong>actual, documented 0.1 behaviour</strong>: the JDBC
     * store is at-least-once via {@code @Idempotent} alone (see the README's "The JDBC store's
     * exactly-once caveat"). {@code IdempotencyAspect} wraps <em>outside</em> the transactional
     * advisor (proven by {@code idempotencyAspectWrapsOutsideTheTransactionalAdvisor} below), which
     * is deliberate - it means a pure replay never opens a database transaction at all - but it
     * also means {@code store.complete()} always runs <em>after</em> the handler's own
     * {@code @Transactional} has already closed, so it opens a brand-new, independent transaction
     * rather than joining one that later rolls back. The record therefore ends up
     * {@code COMPLETED} even though the business transaction rolled back.
     *
     * <p>This test will go red the moment that behaviour changes, intentionally or not. See
     * {@link #row22_transactionalHandlerRollsBack_recordShouldBeInProgressUnderExactlyOnceAspiration()}
     * for the exactly-once target it would need to flip to.
     */
    @Test
    void row22_transactionalHandlerRollsBack_recordStillCompletesBecauseStoreIsAtLeastOnce() throws Exception {
        txController.reset();
        String key = "tx-row22-" + System.nanoTime();

        mockMvc.perform(post("/m/tx-rollback").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated());

        assertThat(txController.callCount()).isEqualTo(1);

        // The business transaction rolled back, but complete() had already opened and committed
        // its own independent transaction by then - the record is COMPLETED, not IN_PROGRESS. This
        // is the documented at-least-once contract, proven end-to-end through the real HTTP/AOP
        // path (JdbcIdempotencyStoreTest proves the same thing at the isolated store level).
        var recordAfterRollback = store.find(findStorageKeyFor(key));
        assertThat(recordAfterRollback).isPresent();
        assertThat(recordAfterRollback.get().state()).isEqualTo(State.COMPLETED);

        // A retry therefore replays the COMPLETED record rather than re-executing - the visible,
        // documented consequence of "at-least-once via @Idempotent alone".
        mockMvc.perform(post("/m/tx-rollback").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Idempotent-Replay", "true"));
        assertThat(txController.callCount()).isEqualTo(1);
    }

    /**
     * The specification for exactly-once via transaction joining, kept executable but disabled
     * until that work lands. The intended approach: on the execute path only, wrap
     * {@code pjp.proceed()} plus {@code store.complete()} in a programmatic transaction
     * ({@code TransactionTemplate}) so a handler's own {@code @Transactional(REQUIRED)} joins it
     * rather than opening and closing its own beforehand.
     *
     * <p>Known edge cases that design has to handle: the claim must still commit independently to
     * stay visible to concurrent callers, so a rollback leaves an orphan {@code IN_PROGRESS} row
     * the aspect must {@code release()} in {@code REQUIRES_NEW} - including on
     * {@code UnexpectedRollbackException} from a silent {@code setRollbackOnly()}. It would also
     * put non-{@code @Transactional} handlers inside a transaction they never asked for, so it
     * needs gating behind a property rather than becoming the default.
     */
    @Test
    @Disabled("Exactly-once via transaction joining - targeted at 0.2, tracked in issue #1; see this method's javadoc "
            + "for the intended design and its edge cases.")
    void row22_transactionalHandlerRollsBack_recordShouldBeInProgressUnderExactlyOnceAspiration() throws Exception {
        txController.reset();
        String key = "tx-row22-" + System.nanoTime();

        mockMvc.perform(post("/m/tx-rollback").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated());

        assertThat(txController.callCount()).isEqualTo(1);

        // The business transaction rolled back after the aspect wrote the COMPLETED record inside
        // it; the record must not have survived as COMPLETED (same assertion as
        // JdbcIdempotencyStoreTest, now proven through the real HTTP+AOP path).
        var recordAfterRollback = store.find(findStorageKeyFor(key));
        assertThat(recordAfterRollback).isPresent();
        assertThat(recordAfterRollback.get().state()).isEqualTo(State.IN_PROGRESS);

        // A retry must therefore re-execute rather than replay a "completed" response that never
        // durably existed.
        mockMvc.perform(post("/m/tx-rollback").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated());
        assertThat(txController.callCount()).isEqualTo(2);
    }

    /**
     * Asserts on the actual advisor order, not merely on a test passing: {@code IdempotencyAspect}'s
     * order must be numerically lower (higher priority, more "outer") than the transactional
     * advisor's, which is why a pure replay never needs to open a database transaction at all -
     * {@code pjp.proceed()} is skipped entirely.
     *
     * <p>This is also precisely why the at-least-once behaviour above holds: outer wrapping means
     * {@code store.complete()} always runs after the business transaction has already closed.
     * Correct ordering and the exactly-once gap are two sides of the same design tradeoff, not a
     * contradiction.
     */
    @Test
    void idempotencyAspectWrapsOutsideTheTransactionalAdvisor() {
        assertThat(txController).isInstanceOf(org.springframework.aop.framework.Advised.class);
        var advised = (org.springframework.aop.framework.Advised) txController;

        int aspectOrder = Integer.MIN_VALUE;
        int transactionalOrder = Integer.MAX_VALUE;
        boolean foundAspect = false;
        boolean foundTransactional = false;
        for (var advisor : advised.getAdvisors()) {
            if (advisor instanceof org.springframework.core.Ordered ordered) {
                String description = advisor.toString();
                if (description.contains("IdempotencyAspect")) {
                    aspectOrder = ordered.getOrder();
                    foundAspect = true;
                } else if (advisor instanceof org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor) {
                    transactionalOrder = ordered.getOrder();
                    foundTransactional = true;
                }
            }
        }

        assertThat(foundAspect).as("IdempotencyAspect must be one of the advisors on this proxy").isTrue();
        assertThat(foundTransactional).as("the transactional advisor must be one of the advisors on this proxy").isTrue();
        assertThat(aspectOrder).as("lower order = outer wrapping = applied first")
                .isLessThan(transactionalOrder);
    }

    @Autowired
    private io.github.benhendayoussef.idempotency.internal.IdempotencyKeyComposer composer;

    private String findStorageKeyFor(String clientKey) {
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/m/tx-rollback");
        request.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/m/tx-rollback");
        return composer.compose(clientKey, "", request);
    }

    @RestController
    static class TxRollbackController {
        final AtomicInteger callCount = new AtomicInteger();

        void reset() {
            callCount.set(0);
        }

        int callCount() {
            return callCount.get();
        }

        @Idempotent
        @Transactional
        @PostMapping("/m/tx-rollback")
        ResponseEntity<Map<String, Object>> rollingBack(@RequestBody Map<String, Object> body) {
            callCount.incrementAndGet();
            // Simulate the business logic itself deciding, after doing its work, that the
            // transaction must not commit - the same "returns normally but the tx rolls back"
            // shape as JdbcIdempotencyStoreTest.completeJoinsTheCallersTransactionAndRollsBackWithIt.
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("done", true));
        }
    }
}
