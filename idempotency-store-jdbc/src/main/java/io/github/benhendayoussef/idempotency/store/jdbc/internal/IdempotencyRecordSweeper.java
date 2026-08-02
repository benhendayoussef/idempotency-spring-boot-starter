package io.github.benhendayoussef.idempotency.store.jdbc.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Deletes expired rows. Off by default ({@code idempotency.jdbc.sweeper-enabled=false}) - the
 * atomic reclaim in {@link JdbcIdempotencyStore#claim} already makes this unnecessary on the hot
 * path; this only reclaims disk space for callers who never retry.
 */
public class IdempotencyRecordSweeper {

    private final NamedParameterJdbcTemplate jdbc;
    private final String deleteExpiredSql;

    public IdempotencyRecordSweeper(NamedParameterJdbcTemplate jdbc, String tableName) {
        this.jdbc = jdbc;
        this.deleteExpiredSql = "DELETE FROM %s WHERE expires_at < :now".formatted(tableName);
    }

    public int sweep() {
        return jdbc.update(deleteExpiredSql, Map.of("now", Timestamp.from(Instant.now())));
    }
}
