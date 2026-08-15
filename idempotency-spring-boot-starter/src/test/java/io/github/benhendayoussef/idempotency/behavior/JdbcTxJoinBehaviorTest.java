package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.benhendayoussef.idempotency.api.Idempotent;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord.State;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.internal.IdempotencyKeyComposer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The JDBC store with {@code idempotency.jdbc.join-transaction=true}: the handler and the completion
 * write share one transaction, so they commit or roll back together.
 *
 * <p>This class extends {@link AbstractIdempotencyBehaviorTest} deliberately. Joined mode changes
 * how <em>every</em> advised method executes, not only transactional ones, so the whole behavioural
 * matrix - fingerprinting, TTL, scopes, wait/fail-fast, the 32-thread concurrency soak - has to hold
 * under it too, not just the handful of transactional cases below. The inherited suite is the real
 * evidence that this property is safe to switch on; the methods in this class only cover what
 * transaction joining adds on top.
 *
 * <p>The business table exists so atomicity is observable at all: "exactly-once" is a claim about
 * the business row and the completion record sharing a fate, which cannot be asserted from the
 * idempotency table alone.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {AbstractIdempotencyBehaviorTest.TestApp.class, JdbcTxJoinBehaviorTest.TxJoinController.class,
                JdbcTxJoinBehaviorTest.CountingTransactionManagerConfig.class},
        properties = {
                "idempotency.store=jdbc",
                "idempotency.jdbc.join-transaction=true",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/idempotency/postgres.sql,classpath:db/txjoin/business.sql",
                TestAutoconfigExcludes.EXCLUDE_SECURITY
        })
class JdbcTxJoinBehaviorTest extends AbstractIdempotencyBehaviorTest {

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
    private TxJoinController controller;

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private IdempotencyKeyComposer composer;

    @Autowired
    private CountingTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetJoinFixtures() {
        controller.reset();
        txManager.reset();
    }

    // ==============================================================================================
    // Row 1 - the acceptance criterion. Moved here from JdbcIdempotencyBehaviorTest, where it was
    // @Disabled pending this work (issue #1).
    // ==============================================================================================

    /**
     * The exactly-once acceptance test. A {@code @Transactional} handler rolls its transaction back;
     * because the completion write now shares that transaction, no {@code COMPLETED} record survives
     * and a retry genuinely re-executes rather than replaying a response that never durably existed.
     *
     * <p>The state assertion is "not {@code COMPLETED}" rather than the original
     * "present and {@code IN_PROGRESS}". That is not a weakening, it is the only form the two
     * halves of this test can both hold in: C3 requires the aspect to {@code release()} the orphaned
     * claim, {@code release()} is a hard {@code DELETE}, and a surviving {@code IN_PROGRESS} row
     * would make the retry below hit {@code AlreadyHeld} and return 409 instead of re-executing.
     * Both halves of the original intent - a rolled-back completion must not survive, and a retry
     * must re-execute - are asserted exactly as before. See V02-PHASE-A-REPORT.md.
     */
    @Test
    void row1_transactionalHandlerRollsBack_noCompletedRecordSurvivesAndRetryReExecutes() throws Exception {
        String key = "txjoin-row1-" + System.nanoTime();

        mockMvc.perform(post("/m/tx-rollback").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated());

        assertThat(controller.rollbackCount()).isEqualTo(1);

        var afterRollback = store.find(storageKeyFor("/m/tx-rollback", key));
        assertThat(afterRollback.map(r -> r.state()))
                .as("the completion write rolled back with the business transaction, so nothing "
                        + "COMPLETED may survive - the record is either gone (released) or IN_PROGRESS")
                .isNotEqualTo(java.util.Optional.of(State.COMPLETED));

        mockMvc.perform(post("/m/tx-rollback").header("Idempotency-Key", key)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated());
        assertThat(controller.rollbackCount())
                .as("a retry must re-execute, not replay a response that was never committed")
                .isEqualTo(2);
    }

    // ==============================================================================================
    // Row 3 - "crash between handler commit and completion write" is impossible by construction.
    // ==============================================================================================

    /**
     * There is no window to crash in: the handler's work and the completion write go through one
     * physical begin and one physical commit. Under the default (separate-commits) mode the same
     * request produces two of each - that gap is the at-least-once defect this property closes.
     */
    @Test
    void row3_handlerAndCompletionWriteShareExactlyOnePhysicalCommit() throws Exception {
        String key = "txjoin-row3-" + System.nanoTime();

        mockMvc.perform(post("/m/tx-commit").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"id\":\"" + key + "\"}"))
                .andExpect(status().isCreated());

        assertThat(txManager.physicalBegins()).as("one transaction, not one per participant").isEqualTo(1);
        assertThat(txManager.physicalCommits()).as("one commit covering business data + completion record")
                .isEqualTo(1);
        assertThat(txManager.physicalRollbacks()).isZero();

        assertThat(businessRowCount(key)).isEqualTo(1);
        assertThat(store.find(storageKeyFor("/m/tx-commit", key)).map(r -> r.state()))
                .contains(State.COMPLETED);
    }

    /**
     * The third rollback path C3 lists, and the only one that cannot be reached by throwing from the
     * handler: the handler returns cleanly, the completion write succeeds, and then <em>the commit
     * itself</em> fails. A deferred unique constraint makes that reachable - the duplicate is
     * accepted by the {@code INSERT} and rejected only at {@code COMMIT}.
     *
     * <p>The failure is not an {@code UnexpectedRollbackException}, so it takes the ordinary failure
     * path: unmapped exception, treated as 5xx, key released, error surfaced rather than swallowed.
     */
    @Test
    void row3_commitItselfFails_keyIsReleasedRatherThanLeftOrphanedAndTheErrorSurfaces() throws Exception {
        String key = "txjoin-row3c-" + System.nanoTime();

        // MockMvc rethrows exceptions it has no @ResponseStatus or handler for, rather than
        // rendering the 500 a real servlet container's error dispatch would - so assert on the
        // throw. What matters here is the aspect's cleanup, which happens either way.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        mockMvc.perform(post("/m/tx-commit-fails").header("Idempotency-Key", key)
                                .contentType("application/json").content("{\"id\":\"" + key + "\"}")))
                .as("the commit failure must surface, not be swallowed into a success response")
                .hasRootCauseInstanceOf(java.sql.SQLException.class);

        assertThat(txManager.failedCommits()).as("the shared transaction was attempted and rejected").isEqualTo(1);
        assertThat(store.find(storageKeyFor("/m/tx-commit-fails", key)))
                .as("C3: a failed commit must not strand the claim as IN_PROGRESS")
                .isEmpty();
    }

    // ==============================================================================================
    // Row 4 - @Transactional handler throws 5xx.
    // ==============================================================================================

    @Test
    void row4_transactionalHandlerThrows5xx_businessDataRolledBackKeyReleasedAndReclaimable() throws Exception {
        String key = "txjoin-row4-" + System.nanoTime();
        String body = "{\"id\":\"" + key + "\"}";

        controller.failNext5xx();
        mockMvc.perform(post("/m/tx-flaky").header("Idempotency-Key", key)
                        .contentType("application/json").content(body))
                .andExpect(status().isInternalServerError());

        assertThat(businessRowCount(key)).as("the insert rolled back with the handler").isZero();
        assertThat(store.find(storageKeyFor("/m/tx-flaky", key)))
                .as("5xx releases the key, so no record is left behind at all").isEmpty();

        // Reclaimable: the same key now succeeds cleanly rather than replaying the failure or
        // colliding with an orphaned claim.
        mockMvc.perform(post("/m/tx-flaky").header("Idempotency-Key", key)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated());
        assertThat(businessRowCount(key)).isEqualTo(1);
        assertThat(controller.flakyCount()).isEqualTo(2);
    }

    // ==============================================================================================
    // Row 5 - @Transactional handler throws 4xx. This is the §2.2 decision, option (b).
    // ==============================================================================================

    /**
     * Option (b): the terminal 4xx is written after the rollback, in its own transaction, so it stays
     * replayable. The business data it described is gone - that is the point of exactly-once - but
     * the deterministic client error is still the correct answer to a retry, and it is the same
     * answer the default mode gives. See V02-PHASE-A-REPORT.md for what option (a) would have broken.
     */
    @Test
    void row5_transactionalHandlerThrows4xx_businessDataRolledBackButThe4xxStaysReplayable() throws Exception {
        String key = "txjoin-row5-" + System.nanoTime();
        String body = "{\"id\":\"" + key + "\"}";

        mockMvc.perform(post("/m/tx-fail4xx").header("Idempotency-Key", key)
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());

        assertThat(businessRowCount(key)).as("business data rolled back with the failing handler").isZero();
        assertThat(store.find(storageKeyFor("/m/tx-fail4xx", key)).map(r -> r.state()))
                .as("the 4xx-keeps policy is unchanged by this property")
                .contains(State.COMPLETED);

        mockMvc.perform(post("/m/tx-fail4xx").header("Idempotency-Key", key)
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        assertThat(controller.fail4xxCount())
                .as("the retry replays the stored 4xx rather than re-executing").isEqualTo(1);
    }

    // ==============================================================================================
    // Row 6 - setRollbackOnly() then a normal 201: UnexpectedRollbackException at commit time.
    // ==============================================================================================

    /**
     * The handler marks the <em>shared</em> transaction rollback-only and returns normally, so the
     * template only discovers it at commit, as {@code UnexpectedRollbackException}. The aspect has to
     * absorb that: the rollback is the correct exactly-once outcome, and turning it into a 500 would
     * change the response the caller saw in the default mode, where the same handler returns 201.
     */
    @Test
    void row6_setRollbackOnlyThenNormalReturn_responsePreservedKeyReleasedNoOrphanRow() throws Exception {
        String key = "txjoin-row6-" + System.nanoTime();

        mockMvc.perform(post("/m/tx-rollback-only").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"id\":\"" + key + "\"}"))
                .andExpect(status().isCreated());

        assertThat(txManager.physicalRollbacks()).as("the shared transaction did roll back").isEqualTo(1);
        assertThat(businessRowCount(key)).isZero();
        assertThat(store.find(storageKeyFor("/m/tx-rollback-only", key)))
                .as("C3: the claim must be released, not stranded IN_PROGRESS until its TTL")
                .isEmpty();

        // No orphan means the key is immediately usable again rather than 409-ing for the whole TTL.
        mockMvc.perform(post("/m/tx-rollback-only").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"id\":\"" + key + "\"}"))
                .andExpect(status().isCreated());
        assertThat(controller.rollbackOnlyCount()).isEqualTo(2);
    }

    // ==============================================================================================
    // Row 7 - handler with no @Transactional of its own.
    // ==============================================================================================

    /**
     * Works, and the completion is still atomic with whatever the handler wrote - but note what it
     * costs: a handler that never asked for a transaction now runs inside one, holding a connection
     * for its whole duration. That surprise is exactly why this property is opt-in (C4), and it is
     * called out in the README.
     */
    @Test
    void row7_handlerWithoutTransactional_stillCommitsAtomicallyInOneTransaction() throws Exception {
        String key = "txjoin-row7-" + System.nanoTime();

        mockMvc.perform(post("/m/tx-none").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"id\":\"" + key + "\"}"))
                .andExpect(status().isCreated());

        assertThat(txManager.physicalBegins()).isEqualTo(1);
        assertThat(txManager.physicalCommits()).isEqualTo(1);
        assertThat(businessRowCount(key)).isEqualTo(1);
        assertThat(store.find(storageKeyFor("/m/tx-none", key)).map(r -> r.state()))
                .contains(State.COMPLETED);
    }

    // ==============================================================================================
    // Row 8 - handler annotated REQUIRES_NEW opts out of joining, so atomicity does not hold.
    // ==============================================================================================

    /**
     * Documenting a real hole rather than pretending it does not exist. {@code REQUIRES_NEW} suspends
     * the aspect's transaction and runs in its own, so the two no longer share a fate: here the
     * handler's own transaction rolls back while the aspect's commits the completion record. The
     * result is a {@code COMPLETED} record describing business data that does not exist - precisely
     * the at-least-once failure this property is meant to remove, reintroduced by the handler's own
     * propagation choice.
     *
     * <p>Not fixable from inside the library: {@code REQUIRES_NEW} is an explicit instruction to not
     * participate, and overriding it would be a worse surprise than the gap. Documented in the
     * README's joined-mode caveats.
     */
    @Test
    void row8_requiresNewHandlerOptsOutOfJoining_soAtomicityDoesNotHold() throws Exception {
        String key = "txjoin-row8-" + System.nanoTime();

        mockMvc.perform(post("/m/tx-requires-new").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"id\":\"" + key + "\"}"))
                .andExpect(status().isCreated());

        assertThat(txManager.physicalBegins())
                .as("two physical transactions: the aspect's, plus the suspended-and-replaced inner one")
                .isEqualTo(2);
        assertThat(businessRowCount(key))
                .as("the inner transaction rolled back independently").isZero();
        assertThat(store.find(storageKeyFor("/m/tx-requires-new", key)).map(r -> r.state()))
                .as("...but the outer transaction still committed the completion record - the two "
                        + "did not share a fate, which is the documented REQUIRES_NEW caveat")
                .contains(State.COMPLETED);
    }

    // ==============================================================================================
    // Row 9 - C2: the replay path must open zero transactions.
    // ==============================================================================================

    /**
     * The reason the aspect advises outside the transactional advisor in the first place. A replay
     * answers from the store and never calls {@code pjp.proceed()}, so it must not touch the
     * transaction manager at all - not "open a short one", zero. Asserted against a real counting
     * manager rather than assumed from the ordering.
     */
    @Test
    void row9_replayOfCompletedKey_opensZeroTransactions() throws Exception {
        String key = "txjoin-row9-" + System.nanoTime();
        String body = "{\"id\":\"" + key + "\"}";

        mockMvc.perform(post("/m/tx-commit").header("Idempotency-Key", key)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated());

        txManager.reset();

        mockMvc.perform(post("/m/tx-commit").header("Idempotency-Key", key)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"));

        assertThat(controller.commitCount()).as("nothing re-executed").isEqualTo(1);
        assertThat(txManager.getTransactionCalls())
                .as("C2: a replay must not interact with the transaction manager at all")
                .isZero();
    }

    // ==============================================================================================
    // Helpers and fixtures
    // ==============================================================================================

    private int businessRowCount(String id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM tx_join_business WHERE id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }

    private String storageKeyFor(String path, String clientKey) {
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", path);
        request.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, path);
        return composer.compose(clientKey, "", request);
    }

    /**
     * Replaces Boot's auto-configured transaction manager (which backs off on
     * {@code @ConditionalOnMissingBean(TransactionManager.class)}) with a counting decorator over the
     * real one. Decorating rather than mocking keeps every transaction genuinely applied to the
     * Testcontainers Postgres - the counts are observations, not a substitute for the database.
     */
    @Configuration(proxyBeanMethods = false)
    static class CountingTransactionManagerConfig {
        @Bean
        CountingTransactionManager transactionManager(DataSource dataSource) {
            return new CountingTransactionManager(new DataSourceTransactionManager(dataSource));
        }
    }

    static class CountingTransactionManager implements PlatformTransactionManager {

        private final PlatformTransactionManager delegate;
        private final AtomicInteger getTransactionCalls = new AtomicInteger();
        private final AtomicInteger physicalBegins = new AtomicInteger();
        private final AtomicInteger physicalCommits = new AtomicInteger();
        private final AtomicInteger physicalRollbacks = new AtomicInteger();
        private final AtomicInteger failedCommits = new AtomicInteger();

        CountingTransactionManager(PlatformTransactionManager delegate) {
            this.delegate = delegate;
        }

        void reset() {
            getTransactionCalls.set(0);
            physicalBegins.set(0);
            physicalCommits.set(0);
            physicalRollbacks.set(0);
            failedCommits.set(0);
        }

        int getTransactionCalls() {
            return getTransactionCalls.get();
        }

        int physicalBegins() {
            return physicalBegins.get();
        }

        int physicalCommits() {
            return physicalCommits.get();
        }

        int physicalRollbacks() {
            return physicalRollbacks.get();
        }

        int failedCommits() {
            return failedCommits.get();
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            getTransactionCalls.incrementAndGet();
            TransactionStatus status = delegate.getTransaction(definition);
            // isNewTransaction() is what separates a real BEGIN from a participant joining one that
            // is already open - counting calls alone would make joining look identical to not
            // joining, which is the whole thing under test.
            if (status.isNewTransaction()) {
                physicalBegins.incrementAndGet();
            }
            return status;
        }

        @Override
        public void commit(TransactionStatus status) {
            // Both flags must be read before delegating: commit() may roll back instead
            // (rollback-only), and the status is completed by the time it returns. The counting must
            // happen in a finally, because a globally-rollback-only transaction rolls back and then
            // throws UnexpectedRollbackException - the case this whole class exists to observe.
            boolean physical = status.isNewTransaction();
            boolean rollbackOnly = status.isRollbackOnly();
            boolean succeeded = false;
            try {
                delegate.commit(status);
                succeeded = true;
            } finally {
                if (physical) {
                    if (rollbackOnly) {
                        physicalRollbacks.incrementAndGet();
                    } else if (succeeded) {
                        physicalCommits.incrementAndGet();
                    } else {
                        // Attempted and rejected by the database - counted separately, because
                        // lumping it in with successful commits would let a test that expects
                        // durable data pass on a transaction that never became durable.
                        failedCommits.incrementAndGet();
                    }
                }
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            boolean physical = status.isNewTransaction();
            try {
                delegate.rollback(status);
            } finally {
                if (physical) {
                    physicalRollbacks.incrementAndGet();
                }
            }
        }
    }

    @RestController
    static class TxJoinController {

        private final JdbcTemplate jdbc;
        private final AtomicInteger commitCount = new AtomicInteger();
        private final AtomicInteger rollbackCount = new AtomicInteger();
        private final AtomicInteger rollbackOnlyCount = new AtomicInteger();
        private final AtomicInteger flakyCount = new AtomicInteger();
        private final AtomicInteger fail4xxCount = new AtomicInteger();
        private final AtomicInteger noneCount = new AtomicInteger();
        private final AtomicInteger requiresNewCount = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean fail5xxNext =
                new java.util.concurrent.atomic.AtomicBoolean();

        TxJoinController(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        void reset() {
            commitCount.set(0);
            rollbackCount.set(0);
            rollbackOnlyCount.set(0);
            flakyCount.set(0);
            fail4xxCount.set(0);
            noneCount.set(0);
            requiresNewCount.set(0);
            fail5xxNext.set(false);
        }

        void failNext5xx() {
            fail5xxNext.set(true);
        }

        int commitCount() {
            return commitCount.get();
        }

        int rollbackCount() {
            return rollbackCount.get();
        }

        int rollbackOnlyCount() {
            return rollbackOnlyCount.get();
        }

        int flakyCount() {
            return flakyCount.get();
        }

        int fail4xxCount() {
            return fail4xxCount.get();
        }

        private void insert(Map<String, Object> body) {
            jdbc.update("INSERT INTO tx_join_business (id, note) VALUES (?, ?)",
                    String.valueOf(body.get("id")), "written");
        }

        @Idempotent
        @Transactional
        @PostMapping("/m/tx-commit")
        ResponseEntity<Map<String, Object>> commit(@RequestBody Map<String, Object> body) {
            commitCount.incrementAndGet();
            insert(body);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("done", true));
        }

        /**
         * Same shape as {@code JdbcIdempotencyBehaviorTest.TxRollbackController}, so row 1 here and
         * the characterisation test there differ only in the property under test.
         */
        @Idempotent
        @Transactional
        @PostMapping("/m/tx-rollback")
        ResponseEntity<Map<String, Object>> rollingBack(@RequestBody Map<String, Object> body) {
            rollbackCount.incrementAndGet();
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("done", true));
        }

        @Idempotent
        @Transactional
        @PostMapping("/m/tx-rollback-only")
        ResponseEntity<Map<String, Object>> rollbackOnlyAfterWriting(@RequestBody Map<String, Object> body) {
            rollbackOnlyCount.incrementAndGet();
            insert(body);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("done", true));
        }

        @Idempotent
        @Transactional
        @PostMapping("/m/tx-flaky")
        ResponseEntity<Map<String, Object>> flaky(@RequestBody Map<String, Object> body) {
            flakyCount.incrementAndGet();
            insert(body);
            if (fail5xxNext.getAndSet(false)) {
                throw new InfrastructureException();
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("done", true));
        }

        @Idempotent
        @Transactional
        @PostMapping("/m/tx-fail4xx")
        ResponseEntity<Map<String, Object>> fail4xx(@RequestBody Map<String, Object> body) {
            fail4xxCount.incrementAndGet();
            insert(body);
            throw new ClientException();
        }

        /**
         * Two rows sharing a {@code slot} value. The unique constraint on it is
         * {@code DEFERRABLE INITIALLY DEFERRED}, so both inserts succeed and Postgres only rejects
         * the transaction at COMMIT - which is the point.
         */
        @Idempotent
        @Transactional
        @PostMapping("/m/tx-commit-fails")
        ResponseEntity<Map<String, Object>> commitFails(@RequestBody Map<String, Object> body) {
            String id = String.valueOf(body.get("id"));
            jdbc.update("INSERT INTO tx_join_deferred (id, slot) VALUES (?, ?)", id + "-a", id);
            jdbc.update("INSERT INTO tx_join_deferred (id, slot) VALUES (?, ?)", id + "-b", id);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("done", true));
        }

        @Idempotent
        @PostMapping("/m/tx-none")
        ResponseEntity<Map<String, Object>> noTransactionOfItsOwn(@RequestBody Map<String, Object> body) {
            noneCount.incrementAndGet();
            insert(body);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("done", true));
        }

        @Idempotent
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        @PostMapping("/m/tx-requires-new")
        ResponseEntity<Map<String, Object>> requiresNew(@RequestBody Map<String, Object> body) {
            requiresNewCount.incrementAndGet();
            insert(body);
            // Rolls back its own, separate transaction only - the aspect's is suspended and unaware.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("done", true));
        }

        @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        static class InfrastructureException extends RuntimeException {
        }

        @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.BAD_REQUEST)
        static class ClientException extends RuntimeException {
        }
    }
}
