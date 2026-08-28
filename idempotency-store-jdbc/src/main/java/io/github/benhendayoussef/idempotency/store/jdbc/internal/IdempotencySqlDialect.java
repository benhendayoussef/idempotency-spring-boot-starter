package io.github.benhendayoussef.idempotency.store.jdbc.internal;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The database-specific half of {@link JdbcIdempotencyStore}.
 *
 * <p>Only four statements differ between engines, but one of them - the claim - is the whole
 * correctness argument of this library, so it is worth being explicit about what a dialect has to
 * guarantee rather than treating this as string templating.
 *
 * <p><strong>The claim must be atomic</strong>: no read-then-write window, and two concurrent
 * duplicates must never both be told they acquired the key. An expired row must be reclaimable by
 * the claim itself, or a key whose owner crashed would stay locked until a sweeper happened to
 * run. How many statements that takes is the dialect's business - see {@link #tryAcquire}.
 *
 * <p>Dialects must also agree on "now". Every expiry decision is made by the database, never the
 * JVM: with several application instances and any clock skew, comparing against each instance's own
 * {@code Instant.now()} would make the same row look expired on one and live on another. The
 * function chosen must additionally be one that advances <em>within</em> a transaction - Postgres's
 * {@code now()} is frozen at transaction start, which is why {@code clock_timestamp()} is used
 * there.
 *
 * <p>Public only because Spring auto-configuration in another module selects an implementation.
 * Like everything in this package it is <strong>not part of the supported API</strong>.
 */
public interface IdempotencySqlDialect {

    /** Human-readable name, used in startup logging and failure messages. */
    String name();

    /**
     * Atomically take the key, or report that someone else holds it.
     *
     * <p>This is a method rather than a SQL string because the two engines need genuinely different
     * <em>algorithms</em>, not merely different syntax. Postgres can express the whole thing as one
     * statement whose affected-row count is trustworthy; MySQL cannot, because its JDBC driver
     * defaults to reporting <em>matched</em> rather than <em>changed</em> rows, which makes an
     * unchanged duplicate indistinguishable from a fresh insert. Hiding that behind a shared
     * template would have meant every MySQL duplicate silently executing.
     *
     * @return {@code true} if this caller now owns the key - either freshly inserted, or reclaimed
     *         from an expired row. {@code false} means a live row is held by someone else.
     */
    boolean tryAcquire(NamedParameterJdbcTemplate jdbc, String tableName, String id, String fingerprint,
            long ttlMillis);

    /** Parameters: {@code :id}, {@code :fp}, {@code :status}, {@code :payloadType}, {@code :payload}, {@code :ttlMillis}. */
    String completeSql(String tableName);

    /** Parameters: {@code :id}. */
    String releaseSql(String tableName);

    /** Parameters: {@code :id}. Must not return rows that have already expired. */
    String findSql(String tableName);
}
