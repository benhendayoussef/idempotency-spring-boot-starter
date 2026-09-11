package io.github.benhendayoussef.idempotency.autoconfigure;

import io.github.benhendayoussef.idempotency.api.IdempotencyTracer;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Wires {@link IdempotencyTracer} to Micrometer Tracing when the application already traces.
 * Nothing to configure: having a {@code Tracer} bean is the whole opt-in, exactly as having a
 * {@code MeterRegistry} is for {@link IdempotencyMetricsAutoConfiguration}.
 *
 * <p>The same ordering constraint applies as for metrics - this has to run {@code before}
 * {@link IdempotencyAutoConfiguration}, which is where the tracer is read and folded into the
 * request path.
 *
 * <p>{@code @ConditionalOnBean(Tracer.class)} and not merely {@code @ConditionalOnClass}:
 * micrometer-tracing arrives transitively in applications that never configure an exporter or a
 * {@code Tracer}, and injecting one that does not exist would fail startup over something entirely
 * optional.
 */
@AutoConfiguration(before = IdempotencyAutoConfiguration.class)
@ConditionalOnClass(Tracer.class)
@ConditionalOnProperty(prefix = "idempotency.tracing", name = "enabled", matchIfMissing = true)
public class IdempotencyTracingAutoConfiguration {

    @Bean
    @ConditionalOnBean(Tracer.class)
    @ConditionalOnMissingBean(IdempotencyTracer.class)
    public IdempotencyTracer micrometerIdempotencyTracer(Tracer tracer) {
        return new MicrometerIdempotencyTracer(tracer);
    }
}
