package io.github.benhendayoussef.idempotency.internal;

/**
 * Runs a unit of work inside a transaction the caller does not own.
 *
 * <p>This exists because {@code idempotency-core} deliberately has no dependency on
 * {@code spring-tx}: the aspect must be usable with the Redis or in-memory store, neither of which
 * needs a transaction manager. The JDBC store module supplies the only implementation
 * ({@code JdbcTransactionRunner}), and the starter wires it in only when
 * {@code idempotency.jdbc.join-transaction} is enabled.
 *
 * <p>When no implementation is present the aspect keeps its v0.1 behaviour: the handler and the
 * completion write commit separately (at-least-once). Absence is the default and is not an error.
 *
 * <p>Public only so the JDBC module and the auto-configuration can see it across package and
 * module boundaries — like everything else in this package it is <strong>not part of the supported
 * API</strong> and may change without notice.
 */
public interface TransactionRunner {

    /**
     * Executes {@code action} inside a transaction, committing on normal return and rolling back on
     * any throwable.
     *
     * <p>Implementations must let the original throwable propagate rather than wrapping it: the
     * aspect inspects it to decide whether the idempotency key is released or completed with a
     * terminal error record.
     *
     * @throws Throwable whatever {@code action} threw, or a rollback failure raised at commit time
     *                   (notably {@code UnexpectedRollbackException} when the handler marked the
     *                   shared transaction rollback-only and then returned normally)
     */
    <T> T inTransaction(TransactionCallback<T> action) throws Throwable;

    /** The work to run transactionally. Allowed to throw, because the advised method can. */
    @FunctionalInterface
    interface TransactionCallback<T> {
        T run() throws Throwable;
    }
}
