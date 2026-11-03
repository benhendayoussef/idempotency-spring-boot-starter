package io.github.benhendayoussef.idempotency.behavior;

import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.api.Idempotent;
import io.github.benhendayoussef.idempotency.internal.IdempotencyKeyComposer;
import io.github.benhendayoussef.idempotency.store.jdbc.internal.IdempotencySqlDialect;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {AbstractIdempotencyBehaviorTest.TestApp.class, OracleIdempotencyBehaviorTest.TxRollbackController.class},
        properties = {
                "idempotency.store=jdbc",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/idempotency/oracle.sql",
                TestAutoconfigExcludes.EXCLUDE_SECURITY
        })
class OracleIdempotencyBehaviorTest extends AbstractIdempotencyBehaviorTest {

        @Container
        private static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart");

        @DynamicPropertySource
        static void oracleProperties(DynamicPropertyRegistry registry) {
                registry.add("spring.datasource.url", ORACLE::getJdbcUrl);
                registry.add("spring.datasource.username", ORACLE::getUsername);
                registry.add("spring.datasource.password", ORACLE::getPassword);
        }

        @Autowired
        private IdempotencySqlDialect dialect;

        @Autowired
        private TxRollbackController txController;

        @Autowired
        private IdempotencyStore store;

        @Autowired
        private IdempotencyKeyComposer composer;

        @Autowired
        private MockMvc mockMvc;


        @Test
        void autoDetectionPicksTheOracleDialectWithoutBeingTold() throws Exception {
                // idempotency.jdbc.dialect is left at AUTO above, so this asserts detection rather than
                // configuration - the case every Oracle user actually hits.
                //
                // Detection is deferred until the store is first used; that laziness is asserted in
                // IdempotencyAutoConfigurationTest, which can use a fresh context. Here the context is
                // shared with the inherited suite, so the dialect may already be resolved - what this test
                // proves is that detection reached the right answer against a real Oracle.
                mockMvc.perform(post("/m/echo").header("Idempotency-Key", "detect-" + System.nanoTime())
                                .contentType("application/json").content("{\"a\":1}"))
                        .andExpect(status().isCreated());

                assertThat(dialect.name()).isEqualTo("Oracle");
        }

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
         * <p>This test will go red the moment that behaviour changes, intentionally or not - which makes
         * it the backward-compatibility gate for {@code idempotency.jdbc.join-transaction}: this context
         * leaves the property at its default, and the default must keep behaving exactly as 0.1 did.
         * {@code JdbcTxJoinBehaviorTest} runs the same scenario with the property on and asserts the
         * opposite outcome.
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
                assertThat(recordAfterRollback.get().state()).isEqualTo(IdempotencyRecord.State.COMPLETED);

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