package io.github.benhendayoussef.idempotency.autoconfigure;

import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.api.NoStoreConfiguredException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Runs after every store candidate has had a chance to register. If none did, fail startup with
 * a clear message ({@link MissingStoreFailureAnalyzer}) instead of an aspect silently missing
 * its {@link IdempotencyStore} dependency.
 */
@AutoConfiguration(after = IdempotencyAutoConfiguration.class)
@ConditionalOnProperty(prefix = "idempotency", name = "enabled", matchIfMissing = true)
// Any web application: a WebFlux app with no usable store needs the same actionable startup
// failure a servlet one gets, rather than a NoSuchBeanDefinitionException from the aspect.
@ConditionalOnWebApplication
public class IdempotencyStoreFallbackAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore missingIdempotencyStore() {
        throw new NoStoreConfiguredException();
    }
}
