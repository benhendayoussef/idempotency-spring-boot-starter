package io.github.benhendayoussef.idempotency.store.jdbc.internal;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Postgres. The original dialect, extracted from {@link JdbcIdempotencyStore} unchanged when MySQL
 * support was added - the SQL here is byte-for-byte what shipped in 0.1 and 0.2.
 *
 * <p>{@code clock_timestamp()} and not {@code now()}: the latter is frozen at transaction start, so
 * a long-running transaction would keep comparing against a stale "now" and see expired rows as
 * live.
 */
public final class PostgresDialect implements IdempotencySqlDialect {

    @Override
    public String name() {
        return "PostgreSQL";
    }

    @Override
    public boolean tryAcquire(NamedParameterJdbcTemplate jdbc, String tableName, String id,
            String fingerprint, long ttlMillis) {
        int rows = jdbc.update(claimSql(tableName), new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("fp", fingerprint)
                .addValue("ttlMillis", ttlMillis));
        // Postgres reports exactly one row for both the insert and the reclaiming update, and
        // zero when the WHERE excluded a live row. The pgjdbc driver has no found-rows mode, so
        // unlike MySQL this count means what it says.
        return rows == 1;
    }

    private String claimSql(String tableName) {
        // ON CONFLICT ... DO UPDATE ... WHERE is the whole trick: the WHERE is evaluated against the
        // existing row, so a live row matches nothing and the statement reports zero rows without
        // ever touching it.
        return """
                INSERT INTO %s
                    (id, state, fingerprint, status, payload_type, payload, created_at, expires_at)
                VALUES (:id, 'IN_PROGRESS', :fp, NULL, NULL, NULL, clock_timestamp(),
                        clock_timestamp() + (:ttlMillis * INTERVAL '1 millisecond'))
                ON CONFLICT (id) DO UPDATE SET
                    state = 'IN_PROGRESS', fingerprint = :fp, status = NULL,
                    payload_type = NULL, payload = NULL, created_at = clock_timestamp(),
                    expires_at = clock_timestamp() + (:ttlMillis * INTERVAL '1 millisecond')
                WHERE %s.expires_at < clock_timestamp()
                """.formatted(tableName, tableName);
    }

    @Override
    public String completeSql(String tableName) {
        return """
                UPDATE %s SET
                    state = 'COMPLETED', fingerprint = :fp, status = :status,
                    payload_type = :payloadType, payload = :payload,
                    expires_at = clock_timestamp() + (:ttlMillis * INTERVAL '1 millisecond')
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
                FROM %s WHERE id = :id AND expires_at >= clock_timestamp()
                """.formatted(tableName);
    }

}
