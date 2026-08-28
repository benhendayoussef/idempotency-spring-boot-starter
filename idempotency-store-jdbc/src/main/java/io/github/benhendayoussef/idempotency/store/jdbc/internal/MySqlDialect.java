package io.github.benhendayoussef.idempotency.store.jdbc.internal;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * MySQL and MariaDB.
 *
 * <p>{@code CURRENT_TIMESTAMP(6)} is the counterpart to Postgres's {@code clock_timestamp()}: MySQL
 * evaluates it per statement rather than freezing it for the transaction, which is the property the
 * claim needs. The {@code (6)} matters - without it MySQL truncates to whole seconds, so a TTL
 * shorter than a second would round to nothing and two claims in the same second would compare as
 * equal.
 */
public final class MySqlDialect implements IdempotencySqlDialect {

    private static final String EXPIRY =
            "DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL (:ttlMillis * 1000) MICROSECOND)";

    @Override
    public String name() {
        return "MySQL";
    }

    /**
     * Reclaim-then-insert, rather than the single {@code INSERT ... ON DUPLICATE KEY UPDATE} the
     * Postgres dialect's shape would suggest.
     *
     * <p>The obvious upsert does not work here, and fails in the worst possible way. Connector/J
     * defaults to {@code useAffectedRows=false}, which sets {@code CLIENT_FOUND_ROWS} and makes the
     * driver report <em>matched</em> rows instead of <em>changed</em> ones. A duplicate that
     * deliberately changed nothing then reports 1 - identical to a fresh insert - so every duplicate
     * reads as a successful claim and executes. That is not a subtle degradation; it is the library
     * doing the exact opposite of its job, and it depends on a connection-string option the
     * application owns rather than this code.
     *
     * <p>Both statements below are immune to that, because each is conditional in its own
     * {@code WHERE} clause rather than in its update count:
     *
     * <ol>
     *   <li>The {@code UPDATE} matches only an <em>expired</em> row. A live row is excluded by the
     *       predicate, so it matches zero rows under either driver mode. When it does match, it
     *       always changes values, so matched and changed agree.</li>
     *   <li>If nothing was reclaimed, the {@code INSERT} either succeeds - the key was absent - or
     *       violates the primary key, which Spring surfaces as {@link DuplicateKeyException}. That
     *       is an exception, not a count, so no driver setting can blur it.</li>
     * </ol>
     *
     * <p>Concurrency holds because InnoDB takes a row lock for the {@code UPDATE} and a unique-index
     * lock for the {@code INSERT}. Two callers racing an expired row: the first reclaims it and
     * pushes {@code expires_at} into the future, so the second no longer matches the predicate, falls
     * through, and collides on the primary key. Exactly one wins.
     */
    @Override
    public boolean tryAcquire(NamedParameterJdbcTemplate jdbc, String tableName, String id,
            String fingerprint, long ttlMillis) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("fp", fingerprint)
                .addValue("ttlMillis", ttlMillis);

        if (jdbc.update(reclaimExpiredSql(tableName), params) > 0) {
            return true;
        }
        try {
            jdbc.update(insertSql(tableName), params);
            return true;
        } catch (DuplicateKeyException alreadyHeld) {
            // Someone else holds a live row. The caller reads it back to build the AlreadyHeld
            // result; swallowing the exception here is the whole signal.
            return false;
        }
    }

    private String reclaimExpiredSql(String tableName) {
        return """
                UPDATE %s SET
                    state = 'IN_PROGRESS', fingerprint = :fp, status = NULL,
                    payload_type = NULL, payload = NULL, created_at = CURRENT_TIMESTAMP(6),
                    expires_at = %s
                WHERE id = :id AND expires_at < CURRENT_TIMESTAMP(6)
                """.formatted(tableName, EXPIRY);
    }

    private String insertSql(String tableName) {
        return """
                INSERT INTO %s
                    (id, state, fingerprint, status, payload_type, payload, created_at, expires_at)
                VALUES (:id, 'IN_PROGRESS', :fp, NULL, NULL, NULL, CURRENT_TIMESTAMP(6), %s)
                """.formatted(tableName, EXPIRY);
    }

    @Override
    public String completeSql(String tableName) {
        return """
                UPDATE %s SET
                    state = 'COMPLETED', fingerprint = :fp, status = :status,
                    payload_type = :payloadType, payload = :payload,
                    expires_at = %s
                WHERE id = :id
                """.formatted(tableName, EXPIRY);
    }

    @Override
    public String releaseSql(String tableName) {
        return "DELETE FROM %s WHERE id = :id".formatted(tableName);
    }

    @Override
    public String findSql(String tableName) {
        return """
                SELECT state, fingerprint, status, payload_type, payload, created_at
                FROM %s WHERE id = :id AND expires_at >= CURRENT_TIMESTAMP(6)
                """.formatted(tableName);
    }
}
