package io.github.benhendayoussef.idempotency.behavior;

/**
 * Shared {@code spring.autoconfigure.exclude} property strings for test contexts in this package.
 * {@code spring-boot-starter-jdbc} and {@code spring-boot-starter-security} are both on this
 * module's test classpath (needed by the JDBC matrix tests and the Spring Security ordering test
 * respectively), so any test context that doesn't itself configure a {@code DataSource} or a
 * {@code SecurityFilterChain} must exclude the corresponding autoconfiguration explicitly, or Boot
 * tries to supply its own default (an unconfigured DataSource fails eagerly; a default security
 * filter chain requires authentication for every request) - neither of which the test wants.
 */
final class TestAutoconfigExcludes {

    private static final String NO_DATASOURCE =
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration";
    // Excludes only the autoconfiguration that installs Boot's default restrictive filter chain -
    // not the base SecurityAutoConfiguration, which also registers the SecurityProperties bean
    // other core Boot autoconfigurations (error handling) depend on regardless of whether a
    // security filter chain is wanted.
    private static final String NO_SECURITY =
            "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration";

    /** For JDBC-store tests: they need a real DataSource, just not Boot's default security. */
    static final String EXCLUDE_SECURITY = "spring.autoconfigure.exclude=" + NO_SECURITY;

    /** For Redis-store/in-memory-store tests: need neither a DataSource nor default security. */
    static final String EXCLUDE_DATASOURCE_AND_SECURITY =
            "spring.autoconfigure.exclude=" + NO_DATASOURCE + "," + NO_SECURITY;

    private TestAutoconfigExcludes() {
    }
}
