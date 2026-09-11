package io.github.benhendayoussef.idempotency.autoconfigure;

import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link IdempotencyEndpoint} when actuator is present and the endpoint is exposed.
 *
 * <p>{@code @ConditionalOnAvailableEndpoint} is what makes this opt-in rather than a surprise:
 * actuator exposes only {@code health} and {@code info} over HTTP by default, so the endpoint stays
 * unreachable until an operator names it in {@code management.endpoints.web.exposure.include}.
 * Given it can evict idempotency keys, that default is the right one.
 *
 * <p>Ordered after {@link IdempotencyAutoConfiguration} because the endpoint needs the store bean
 * that class selects, and {@code @ConditionalOnBean} is only reliable once the bean it looks for has
 * already been defined.
 */
@AutoConfiguration(after = IdempotencyAutoConfiguration.class)
@ConditionalOnClass({ Endpoint.class, ConditionalOnAvailableEndpoint.class })
@ConditionalOnProperty(prefix = "idempotency", name = "enabled", matchIfMissing = true)
public class IdempotencyEndpointAutoConfiguration {

    @Bean
    @ConditionalOnBean(IdempotencyStore.class)
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint(endpoint = IdempotencyEndpoint.class)
    public IdempotencyEndpoint idempotencyEndpoint(IdempotencyStore store) {
        return new IdempotencyEndpoint(store);
    }
}
