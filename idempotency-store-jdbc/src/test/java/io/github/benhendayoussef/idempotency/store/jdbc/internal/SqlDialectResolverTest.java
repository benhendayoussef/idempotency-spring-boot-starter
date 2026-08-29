package io.github.benhendayoussef.idempotency.store.jdbc.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Product-name mapping. Cheap to test directly, and worth doing so: the consequence of getting it
 * wrong is not an error but the wrong SQL dialect running successfully against the wrong engine.
 */
class SqlDialectResolverTest {

    @Test
    void postgresVariantsMapToThePostgresDialect() {
        assertThat(SqlDialectResolver.forProductName("PostgreSQL").name()).isEqualTo("PostgreSQL");
    }

    @Test
    void mySqlMapsToTheMySqlDialect() {
        assertThat(SqlDialectResolver.forProductName("MySQL").name()).isEqualTo("MySQL");
    }

    /** MariaDB reports its own name but is compatible with everything the claim uses. */
    @Test
    void mariaDbIsTreatedAsMySql() {
        assertThat(SqlDialectResolver.forProductName("MariaDB").name()).isEqualTo("MySQL");
    }

    @Test
    void matchingIsCaseInsensitive() {
        // JDBC drivers are inconsistent about casing, and this is a plain string comparison.
        assertThat(SqlDialectResolver.forProductName("postgresql").name()).isEqualTo("PostgreSQL");
        assertThat(SqlDialectResolver.forProductName("MYSQL").name()).isEqualTo("MySQL");
    }

    @Test
    void anUnsupportedDatabaseFailsNamingItselfAndTheEscapeHatch() {
        // Failing loudly beats guessing: the wrong dialect would run, not error.
        assertThatThrownBy(() -> SqlDialectResolver.forProductName("H2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("H2")
                .hasMessageContaining("idempotency.jdbc.dialect");
    }

    @Test
    void aNullProductNameIsReportedRatherThanThrowingNullPointer() {
        assertThatThrownBy(() -> SqlDialectResolver.forProductName(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
