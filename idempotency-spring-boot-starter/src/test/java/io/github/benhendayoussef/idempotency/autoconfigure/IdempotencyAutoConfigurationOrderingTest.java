package io.github.benhendayoussef.idempotency.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * Guards the {@code afterName} ordering that makes the starter work on both Spring Boot generations.
 *
 * <p>{@link IdempotencyAutoConfiguration} must be applied <em>after</em> the autoconfigurations that
 * supply a {@code DataSource} or a {@code StringRedisTemplate}, or its {@code @ConditionalOnBean}
 * store beans can be evaluated before those beans exist and silently back off - leaving an
 * application with no store and a startup failure that points at the wrong thing.
 *
 * <p>Boot 4 moved both of those autoconfigurations into per-module packages, so the names differ
 * between generations. The failure mode this test exists for is nasty precisely because it is
 * <strong>silent</strong>: an {@code afterName} entry that matches no class is simply ignored, so
 * naming only one generation still starts up, still passes most tests, and only misbehaves on the
 * other generation under the specific bean-timing that ordering exists to prevent. Nothing about
 * that failure is visible at compile time, which is why it is asserted here by name.
 */
class IdempotencyAutoConfigurationOrderingTest {

    private static final String BOOT4_REDIS =
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration";
    private static final String BOOT4_JDBC =
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration";
    private static final String BOOT3_REDIS =
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration";
    private static final String BOOT3_JDBC =
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration";

    private List<String> afterNames() {
        AutoConfiguration ann = AnnotatedElementUtils.findMergedAnnotation(
                IdempotencyAutoConfiguration.class, AutoConfiguration.class);
        assertThat(ann).as("IdempotencyAutoConfiguration must be an @AutoConfiguration").isNotNull();
        return Arrays.asList(ann.afterName());
    }

    @Test
    void ordersItselfAfterBothGenerationsOfTheStoreAutoConfigurations() {
        assertThat(afterNames())
                .as("dropping any of these silently disables ordering on that Boot generation")
                .contains(BOOT4_REDIS, BOOT4_JDBC, BOOT3_REDIS, BOOT3_JDBC);
    }

    /**
     * Whichever generation is actually on the test classpath, at least one Redis and one JDBC name
     * must resolve to a real class. This is what would catch a typo or an upstream rename - the
     * assertion above only proves the strings are present, not that they mean anything.
     */
    @Test
    void atLeastOneNamedAutoConfigurationResolvesOnTheActiveClasspath() {
        assertThat(resolvable(BOOT4_REDIS, BOOT3_REDIS))
                .as("no Redis autoconfiguration name matched a real class - the afterName entries are "
                        + "either misspelled or the upstream class moved again")
                .isTrue();
        assertThat(resolvable(BOOT4_JDBC, BOOT3_JDBC))
                .as("no DataSource autoconfiguration name matched a real class")
                .isTrue();
    }

    private static boolean resolvable(String... candidates) {
        for (String fqn : candidates) {
            try {
                Class.forName(fqn);
                return true;
            } catch (ClassNotFoundException ignored) {
                // Wrong generation for this classpath - try the next.
            }
        }
        return false;
    }
}
