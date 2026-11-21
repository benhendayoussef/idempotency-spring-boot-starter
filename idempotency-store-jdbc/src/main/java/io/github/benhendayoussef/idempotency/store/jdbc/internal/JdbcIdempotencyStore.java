package io.github.benhendayoussef.idempotency.store.jdbc.internal;

import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord.State;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.dao.DataAccessResourceFailureException;
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
 *
 * <p><strong>Claim ownership.</strong> The owner-aware methods record and check an {@code owner}
 * column added in 1.2. {@code CREATE TABLE IF NOT EXISTS} does not add it to a table created by an
 * earlier version, so the store looks for it once, on first use, and without it falls back to the
 * 1.1 behaviour - unconditional complete and release - after logging an error that names the
 * statement to run. Failing instead would be worse than the bug the column fixes: under the default
 * {@code on-store-failure=proceed}, every request would quietly run without idempotency.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final Log log = LogFactory.getLog(JdbcIdempotencyStore.class);

    private final NamedParameterJdbcTemplate jdbc;
    // Memoized rather than built in the constructor: with dialect=auto the dialect has not
    // been resolved yet at construction time, and asking it for SQL would force a database
    // connection during startup - see LazySqlDialect for why that must not happen.
    private volatile String completeSql;
    private volatile String releaseSql;
    private volatile String findSql;
    private volatile String completeOwnedSql;
    private volatile String releaseOwnedSql;
    // null until first checked, for the same reason: no connection during startup.
    private volatile Boolean ownerColumn;
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
        return claim(key, fingerprint, ttl, null);
    }

    @Override
    public ClaimResult claim(String key, String fingerprint, Duration ttl, String owner) {
        // Even an ownerless claim goes through tryAcquireOwned when the column exists: a reclaim
        // must clear the previous claim's owner, or a run whose claim expired would still pass the
        // owner check against the new one.
        boolean acquired = hasOwnerColumn()
                ? dialect.tryAcquireOwned(jdbc, tableName, key, fingerprint, ttl.toMillis(), owner)
                : dialect.tryAcquire(jdbc, tableName, key, fingerprint, ttl.toMillis());
        if (acquired) {
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
        jdbc.update(completeSql(), completeParams(key, record, ttl));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Like {@link #complete(String, IdempotencyRecord, Duration)}, joins the caller's
     * transaction. A row that is already gone - swept after its claim expired - cannot be updated,
     * so that also returns {@code false}; that response was never going to be stored, before
     * owners or after.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean complete(String key, String owner, IdempotencyRecord record, Duration ttl) {
        Objects.requireNonNull(owner, "owner");
        if (!hasOwnerColumn()) {
            complete(key, record, ttl);
            return true;
        }
        return jdbc.update(completeOwnedSql(), completeParams(key, record, ttl).addValue("owner", owner)) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key) {
        jdbc.update(releaseSql(), Map.of("id", key));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean release(String key, String owner) {
        Objects.requireNonNull(owner, "owner");
        if (!hasOwnerColumn()) {
            release(key);
            return true;
        }
        if (jdbc.update(releaseOwnedSql(), Map.of("id", key, "owner", owner)) == 1) {
            return true;
        }
        // Nothing deleted: either the key is already gone, which counts as released, or a live row
        // held by someone else excluded it. Our own row could not have survived the DELETE.
        return find(key).isEmpty();
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        var rows = jdbc.query(findSql(), new MapSqlParameterSource().addValue("id", key), rowMapper);
        return rows.stream().findFirst();
    }

    private static MapSqlParameterSource completeParams(String key, IdempotencyRecord record, Duration ttl) {
        return new MapSqlParameterSource()
                .addValue("id", key)
                .addValue("fp", record.fingerprint())
                .addValue("status", record.status())
                .addValue("payloadType", record.payloadType())
                .addValue("payload", record.payload())
                .addValue("ttlMillis", ttl.toMillis());
    }

    /**
     * Whether the table has the owner column, checked once.
     *
     * <p>Probed with a query that returns no rows, on a connection of its own rather than the
     * caller's: a failed statement inside a Postgres transaction aborts the whole transaction, and
     * in joined mode that transaction carries the handler's business writes. The first call is
     * normally {@link #claim}, which the aspect makes before opening any transaction, so the extra
     * connection is not taken while another is held.
     *
     * <p>If even the table cannot be read, nothing is decided and the failure propagates as a store
     * failure, to be handled by {@code idempotency.on-store-failure}; the next call checks again.
     */
    private boolean hasOwnerColumn() {
        Boolean known = ownerColumn;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (ownerColumn != null) {
                return ownerColumn;
            }
            DataSource dataSource = jdbc.getJdbcTemplate().getDataSource();
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT id FROM " + tableName + " WHERE 1 = 0").close();
                boolean present;
                try {
                    statement.executeQuery("SELECT owner FROM " + tableName + " WHERE 1 = 0").close();
                    present = true;
                } catch (SQLException missing) {
                    present = false;
                    log.error("Table " + tableName + " has no owner column, so a request whose claim "
                            + "expired mid-handler can overwrite or release a key another request has "
                            + "since claimed (issue #19). Idempotency otherwise works as in 1.1. To fix, "
                            + "run: " + dialect.addOwnerColumnSql(tableName));
                }
                ownerColumn = present;
                return present;
            } catch (SQLException e) {
                throw new DataAccessResourceFailureException("Could not read idempotency table " + tableName, e);
            }
        }
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

    private String completeOwnedSql() {
        String sql = completeOwnedSql;
        return sql != null ? sql : (completeOwnedSql = dialect.completeOwnedSql(tableName));
    }

    private String releaseOwnedSql() {
        String sql = releaseOwnedSql;
        return sql != null ? sql : (releaseOwnedSql = dialect.releaseOwnedSql(tableName));
    }

    private IdempotencyRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Integer status = Optional.ofNullable(rs.getObject("status")).map(s -> (Number)s).map(Number::intValue).orElse(null);
        return new IdempotencyRecord(
                State.valueOf(rs.getString("state")),
                // Oracle stores an empty fingerprint as NULL; the aspect reads "" as "no fingerprint".
                Objects.toString(rs.getString("fingerprint"), ""),
                status,
                rs.getString("payload_type"),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant());
    }
}
