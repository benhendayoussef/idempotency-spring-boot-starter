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
    // Memoized rather than built in the constructor: with dialect=auto the dialect has not
    // been resolved yet at construction time, and asking it for SQL would force a database
    // connection during startup - see LazySqlDialect for why that must not happen.
    private volatile String completeSql;
    private volatile String releaseSql;
    private volatile String findSql;
    private final RowMapper<IdempotencyRecord> rowMapper = this::mapRow;

    private final IdempotencySqlDialect dialect;

    private final String tableName;

    public JdbcIdempotencyStore(NamedParameterJdbcTemplate jdbc, String tableName,
            IdempotencySqlDialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.tableName = tableName;
    }

    @Override
    public ClaimResult claim(String key, String fingerprint, Duration ttl) {
        if (dialect.tryAcquire(jdbc, tableName, key, fingerprint, ttl.toMillis())) {
            return new ClaimResult.Acquired();
        }
        // Lost the claim, so read back whoever holds it. The Optional can still be empty if that
        // row expired or was released in the gap - in which case nobody holds the key any more and
        // the caller is free to proceed.
        return find(key)
                .<ClaimResult>map(ClaimResult.AlreadyHeld::new)
                .orElseGet(ClaimResult.Acquired::new);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void complete(String key, IdempotencyRecord record, Duration ttl) {
        jdbc.update(completeSql(), new MapSqlParameterSource()
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
        jdbc.update(releaseSql(), Map.of("id", key));
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        var rows = jdbc.query(findSql(), new MapSqlParameterSource().addValue("id", key), rowMapper);
        return rows.stream().findFirst();
    }

    private String completeSql() {
        String sql = completeSql;
        return sql != null ? sql : (completeSql = dialect.completeSql(tableName));
    }

    private String releaseSql() {
        String sql = releaseSql;
        return sql != null ? sql : (releaseSql = dialect.releaseSql(tableName));
    }

    private String findSql() {
        String sql = findSql;
        return sql != null ? sql : (findSql = dialect.findSql(tableName));
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
