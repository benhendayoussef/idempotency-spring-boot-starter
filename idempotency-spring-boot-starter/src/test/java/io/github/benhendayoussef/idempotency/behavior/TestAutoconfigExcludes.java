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
public final class TestAutoconfigExcludes {

    // Both generations are named. Boot ignores an exclude entry whose class is not on the
    // classpath, so the pair is safe on either - and naming only one would silently stop
    // excluding anything on the other, letting Boot supply the very DataSource these tests
    // exist to run without.
    private static final String NO_DATASOURCE =
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration";
    // Excludes only the autoconfiguration that installs Boot's default restrictive filter chain -
    // not the base SecurityAutoConfiguration, which also registers the SecurityProperties bean
    // other core Boot autoconfigurations (error handling) depend on regardless of whether a
    // security filter chain is wanted.
    private static final String NO_SECURITY =
            "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration";

    // Folded into both constants above rather than offered separately: actuator is on this
    // module's test classpath for the endpoint tests, so its management filter chain loads in
    // every context - and it needs an HttpSecurity that the security exclusion removes. Excluding
    // one without the other broke every behaviour suite in CI. Boot 4 moved this class from ..actuate.autoconfigure.security.servlet to
    // ..security.autoconfigure.actuate.web.servlet; both are listed, and the one that does not
    // resolve on the active generation is ignored.
    private static final String NO_MANAGEMENT_SECURITY =
            "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration,"
            + "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration";

    /** For tests that configure no DataSource of their own but do want default security. */
    public static final String EXCLUDE_DATASOURCE = "spring.autoconfigure.exclude=" + NO_DATASOURCE;

    /** For JDBC-store tests: they need a real DataSource, just not Boot's default security. */
    public static final String EXCLUDE_SECURITY =
            "spring.autoconfigure.exclude=" + NO_SECURITY + "," + NO_MANAGEMENT_SECURITY;

    /** For Redis-store/in-memory-store tests: need neither a DataSource nor default security. */
    public static final String EXCLUDE_DATASOURCE_AND_SECURITY = "spring.autoconfigure.exclude="
            + NO_DATASOURCE + "," + NO_SECURITY + "," + NO_MANAGEMENT_SECURITY;

    private TestAutoconfigExcludes() {
    }
}
