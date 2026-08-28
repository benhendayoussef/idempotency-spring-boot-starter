package io.github.benhendayoussef.idempotency.store.jdbc.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * Picks the {@link IdempotencySqlDialect} for a {@code DataSource}, either from an explicit setting
 * or by asking the database what it is.
 *
 * <p>Detection happens once, at startup, and fails loudly. A store that silently guessed wrong would
 * not throw - it would issue SQL the engine happens to accept and get the claim semantics subtly
 * wrong, which surfaces as duplicate executions under concurrency rather than as an error anyone
 * could trace back here.
 *
 * <p>Public only for auto-configuration in another module; <strong>not part of the supported
 * API</strong>.
 */
public final class SqlDialectResolver {

    private SqlDialectResolver() {
    }

    /** Detects the dialect from the connection's reported product name. */
    public static IdempotencySqlDialect detect(DataSource dataSource) {
        String product;
        try (Connection connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "idempotency.store=jdbc with idempotency.jdbc.dialect=auto could not connect to the "
                    + "DataSource to detect which database it is. Fix the DataSource, or set "
                    + "idempotency.jdbc.dialect explicitly (postgres or mysql) to skip detection.", e);
        }
        return forProductName(product);
    }

    /**
     * Maps a JDBC product name to a dialect.
     *
     * <p>MariaDB reports itself as "MariaDB" but is wire- and SQL-compatible with MySQL for
     * everything the claim uses, including {@code ON DUPLICATE KEY UPDATE} and
     * {@code CURRENT_TIMESTAMP(6)}, so it maps to the same dialect. Amazon Aurora reports the engine
     * it emulates, so it needs no special case.
     */
    static IdempotencySqlDialect forProductName(String productName) {
        String normalized = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (normalized.contains("postgresql")) {
            return new PostgresDialect();
        }
        if (normalized.contains("mysql") || normalized.contains("mariadb")) {
            return new MySqlDialect();
        }
        throw new IllegalStateException(
                "Unsupported database for idempotency.store=jdbc: the DataSource reports itself as \""
                + productName + "\", and only PostgreSQL and MySQL/MariaDB are implemented. Set "
                + "idempotency.jdbc.dialect explicitly if this database is compatible with one of "
                + "them, or use idempotency.store=redis.");
    }
}
