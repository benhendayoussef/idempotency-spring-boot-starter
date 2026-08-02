package io.github.benhendayoussef.idempotency.store.jdbc.internal;

import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord.State;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres-backed store. {@link #complete(String, IdempotencyRecord, Duration)} joins an
 * already-open transaction on the calling thread if one exists ({@code Propagation.REQUIRED}), so
 * the response record can commit atomically with the business data <strong>only when the caller
 * itself invokes this method from inside a transaction it is already holding open</strong> - not
 * merely because {@code @Idempotent} and {@code @Transactional} are both present on the same
 * handler method. See the README's "JDBC store's exactly-once caveat" for why: the aspect always
 * calls this after the handler's own transaction has already closed. Out of the box, via
 * {@code @Idempotent} alone, this store is at-least-once, same as Redis.
 * {@link #release(String)} runs in {@code REQUIRES_NEW} because it is called from a catch
 * block where the caller's transaction is already marked rollback-only.
 *
 * <p>All expiry decisions are driven by Postgres's own {@code clock_timestamp()}, never the JVM's
 * clock: across multiple app instances with clock skew, comparing against each instance's own
 * {@code Instant.now()} would make the same row look expired on one instance and not another.
 * {@code clock_timestamp()} (not {@code now()}, which is frozen at transaction start) is used
 * deliberately so a long-running transaction still sees the real current time for the comparison.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final String claimSql;
    private final String completeSql;
    private final String releaseSql;
    private final String findSql;
    private final RowMapper<IdempotencyRecord> rowMapper = this::mapRow;

    public JdbcIdempotencyStore(NamedParameterJdbcTemplate jdbc, String tableName) {
        this.jdbc = jdbc;
        this.claimSql = """
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
        this.completeSql = """
                UPDATE %s SET
                    state = 'COMPLETED', fingerprint = :fp, status = :status,
                    payload_type = :payloadType, payload = :payload,
                    expires_at = clock_timestamp() + (:ttlMillis * INTERVAL '1 millisecond')
                WHERE id = :id
                """.formatted(tableName);
        this.releaseSql = "DELETE FROM %s WHERE id = :id".formatted(tableName);
        this.findSql = """
                SELECT state, fingerprint, status, payload_type, payload, created_at
                FROM %s WHERE id = :id AND expires_at >= clock_timestamp()
                """.formatted(tableName);
    }

    @Override
    public ClaimResult claim(String key, String fingerprint, Duration ttl) {
        int rows = jdbc.update(claimSql, new MapSqlParameterSource()
                .addValue("id", key)
                .addValue("fp", fingerprint)
                .addValue("ttlMillis", ttl.toMillis()));
        if (rows == 1) {
            return new ClaimResult.Acquired();
        }
        return find(key)
                .<ClaimResult>map(ClaimResult.AlreadyHeld::new)
                .orElseGet(ClaimResult.Acquired::new);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void complete(String key, IdempotencyRecord record, Duration ttl) {
        jdbc.update(completeSql, new MapSqlParameterSource()
                .addValue("id", key)
                .addValue("fp", record.fingerprint())
                .addValue("status", record.status())
                .addValue("payloadType", record.payloadType())
                .addValue("payload", record.payload())
                .addValue("ttlMillis", ttl.toMillis()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key) {
        jdbc.update(releaseSql, Map.of("id", key));
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        var rows = jdbc.query(findSql, new MapSqlParameterSource().addValue("id", key), rowMapper);
        return rows.stream().findFirst();
    }

    private IdempotencyRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Integer status = (Integer) rs.getObject("status");
        return new IdempotencyRecord(
                State.valueOf(rs.getString("state")),
                rs.getString("fingerprint"),
                status,
                rs.getString("payload_type"),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant());
    }
}
