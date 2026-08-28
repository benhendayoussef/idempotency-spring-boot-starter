package io.github.benhendayoussef.idempotency.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.benhendayoussef.idempotency.api.IdempotencyMetrics;
import io.github.benhendayoussef.idempotency.api.IdempotencyScope;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties;
import io.github.benhendayoussef.idempotency.internal.ArgumentFingerprinter;
import io.github.benhendayoussef.idempotency.webflux.internal.IdempotencyExchangeContextFilter;
import io.github.benhendayoussef.idempotency.webflux.internal.ReactiveIdempotencyAspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures {@code @Idempotent} for WebFlux applications.
 *
 * <p>Mutually exclusive with {@link IdempotencyAutoConfiguration} by construction: that one is
 * {@code @ConditionalOnWebApplication(SERVLET)} and this one is {@code REACTIVE}, so an application
 * gets exactly one aspect and never both. That matters because the two would otherwise each claim
 * the same key for the same request.
 */
@AutoConfiguration
@ConditionalOnClass(ReactiveIdempotencyAspect.class)
@ConditionalOnWebApplication(type = Type.REACTIVE)
@ConditionalOnProperty(prefix = "idempotency", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyWebFluxAutoConfiguration {

    /**
     * Fails startup rather than silently ignoring a scope it cannot honour.
     *
     * <p>{@code USER} and {@code TENANT} resolve the principal from {@code SecurityContextHolder},
     * a ThreadLocal with no meaning on a reactive stack - the reactive equivalent lives in the
     * Reactor context and needs a different resolver SPI. Until that exists, an application that
     * configured per-user scoping and quietly got global scoping instead would be sharing
     * idempotency keys across users, which is a security problem, not a missing feature.
     */
    @Bean
    public ReactiveIdempotencyAspect reactiveIdempotencyAspect(IdempotencyStore store,
            IdempotencyProperties props, ArgumentFingerprinter fingerprinter,
            ObjectMapper idempotencyPayloadObjectMapper, IdempotencyMetrics metrics) {
        if (props.getScope() != IdempotencyScope.GLOBAL) {
            throw new IllegalStateException(
                    "idempotency.scope=" + props.getScope().name().toLowerCase()
                    + " is not supported on WebFlux yet - only GLOBAL is. USER and TENANT resolve the "
                    + "principal from SecurityContextHolder, which has no meaning on a reactive stack. "
                    + "Falling back to GLOBAL silently would share idempotency keys across users, so "
                    + "this fails instead. Set idempotency.scope=global, or use the servlet stack.");
        }
        return new ReactiveIdempotencyAspect(store, props, fingerprinter, idempotencyPayloadObjectMapper, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyExchangeContextFilter idempotencyExchangeContextFilter() {
        return new IdempotencyExchangeContextFilter();
    }
}
