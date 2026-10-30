package io.github.benhendayoussef.idempotency.store.jdbc.internal;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Oracle. Uses MERGE for the atomic claim (Oracle has no ON CONFLICT) and SYSTIMESTAMP for the
 * clock: SYSDATE is second-precision and, like now() in Postgres, would be stale inside a
 * long-running transaction.
 */
public final class OracleDialect implements IdempotencySqlDialect {

    @Override
    public String name() {
        return "Oracle";
    }

    @Override
    public boolean tryAcquire(NamedParameterJdbcTemplate jdbc, String tableName, String id,
                              String fingerprint, long ttlMillis) {
        try {
            int rows = jdbc.update(claimSql(tableName), new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("fp", fingerprint)
                    .addValue("ttlMillis", ttlMillis));
            // MERGE reports 1 for both the insert and the reclaiming update, 0 when the WHEN MATCHED
            // condition excluded a live row. Oracle's JDBC driver returns the true affected-row count.
            return rows == 1;
        } catch (DuplicateKeyException e) {
            // Another concurrent request has already inserted this id; this request did not win the claim
            return false;
        }
    }

    private String claimSql(String tableName) {
        return """
            MERGE INTO %s t
            USING (SELECT :id AS id FROM dual) s
            ON (t.id = s.id)
            WHEN MATCHED THEN UPDATE SET
                t.state = 'IN_PROGRESS', t.fingerprint = :fp, t.status = NULL,
                t.payload_type = NULL, t.payload = NULL, t.created_at = SYSTIMESTAMP,
                t.expires_at = SYSTIMESTAMP + NUMTODSINTERVAL(:ttlMillis / 1000, 'SECOND')
                WHERE t.expires_at < SYSTIMESTAMP
            WHEN NOT MATCHED THEN INSERT
                (id, state, fingerprint, status, payload_type, payload, created_at, expires_at)
            VALUES (:id, 'IN_PROGRESS', :fp, NULL, NULL, NULL, SYSTIMESTAMP,
                    SYSTIMESTAMP + NUMTODSINTERVAL(:ttlMillis / 1000, 'SECOND'))
            """.formatted(tableName);
    }

    @Override
    public String completeSql(String tableName) {
        return """
                UPDATE %s SET
                    state = 'COMPLETED', fingerprint = :fp, status = :status,
                    payload_type = :payloadType, payload = :payload,
                    expires_at = SYSTIMESTAMP + NUMTODSINTERVAL(:ttlMillis / 1000, 'SECOND')
                WHERE id = :id
                """.formatted(tableName);
    }

    @Override
    public String releaseSql(String tableName) {
        return "DELETE FROM %s WHERE id = :id".formatted(tableName);
    }

    @Override
    public String findSql(String tableName) {
        return """
                SELECT state, fingerprint, status, payload_type, payload, created_at
                FROM %s WHERE id = :id AND expires_at >= SYSTIMESTAMP
                """.formatted(tableName);
    }

}