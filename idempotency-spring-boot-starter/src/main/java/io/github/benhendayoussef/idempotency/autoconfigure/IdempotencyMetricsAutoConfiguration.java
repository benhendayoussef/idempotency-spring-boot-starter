package io.github.benhendayoussef.idempotency.autoconfigure;

import io.github.benhendayoussef.idempotency.api.IdempotencyMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Wires {@link IdempotencyMetrics} to Micrometer when the application already has a
 * {@code MeterRegistry}. Nothing to configure: adding {@code spring-boot-starter-actuator} (or any
 * other source of a registry) is the whole opt-in.
 *
 * <p>This is a separate autoconfiguration class rather than a nested one inside
 * {@link IdempotencyAutoConfiguration} because of ordering. That class contributes the no-op
 * fallback behind {@code @ConditionalOnMissingBean}, and a nested member class is processed
 * <em>after</em> its enclosing class's {@code @Bean} methods - the no-op would win the race and the
 * Micrometer implementation would never be registered. Declaring {@code before} makes the ordering
 * explicit and load-bearing rather than incidental.
 *
 * <p>{@code @ConditionalOnBean(MeterRegistry.class)} and not merely {@code @ConditionalOnClass}:
 * Micrometer is on the classpath of plenty of applications that never expose a registry, and
 * injecting one that does not exist would fail startup for something entirely optional.
 */
@AutoConfiguration(before = IdempotencyAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "idempotency.metrics", name = "enabled", matchIfMissing = true)
public class IdempotencyMetricsAutoConfiguration {

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(IdempotencyMetrics.class)
    public IdempotencyMetrics micrometerIdempotencyMetrics(MeterRegistry registry) {
        return new MicrometerIdempotencyMetrics(registry);
    }
}
