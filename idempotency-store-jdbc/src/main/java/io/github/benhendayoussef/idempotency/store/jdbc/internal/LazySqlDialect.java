package io.github.benhendayoussef.idempotency.store.jdbc.internal;

import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Auto-detection that does not touch the database until the first request needs it.
 *
 * <p>Detecting eagerly, at bean creation, seems harmless and is not: it opens a connection during
 * startup, so a database that is merely slow to come up - or deliberately unreachable, which is the
 * documented {@code idempotency.on-store-failure=proceed} scenario - takes the whole application
 * down instead of degrading the way it is supposed to. Resolving on first use keeps an unreachable
 * store a <em>request-time</em> condition, which is where the failure policy can act on it.
 *
 * <p>Public only for auto-configuration in another module; <strong>not part of the supported
 * API</strong>.
 */
public final class LazySqlDialect implements IdempotencySqlDialect {

    private final DataSource dataSource;
    private volatile IdempotencySqlDialect resolved;

    public LazySqlDialect(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private IdempotencySqlDialect delegate() {
        IdempotencySqlDialect local = resolved;
        if (local == null) {
            synchronized (this) {
                local = resolved;
                if (local == null) {
                    local = SqlDialectResolver.detect(dataSource);
                    resolved = local;
                }
            }
        }
        return local;
    }

    /**
     * Deliberately does not resolve. This is used for logging, and a log line is not a good enough
     * reason to open a database connection - least of all during startup, which is the thing this
     * class exists to avoid.
     */
    @Override
    public String name() {
        IdempotencySqlDialect local = resolved;
        return local == null ? "auto (not yet resolved)" : local.name();
    }

    @Override
    public boolean tryAcquire(NamedParameterJdbcTemplate jdbc, String tableName, String id,
            String fingerprint, long ttlMillis) {
        return delegate().tryAcquire(jdbc, tableName, id, fingerprint, ttlMillis);
    }

    @Override
    public String completeSql(String tableName) {
        return delegate().completeSql(tableName);
    }

    @Override
    public String releaseSql(String tableName) {
        return delegate().releaseSql(tableName);
    }

    @Override
    public String findSql(String tableName) {
        return delegate().findSql(tableName);
    }
}
