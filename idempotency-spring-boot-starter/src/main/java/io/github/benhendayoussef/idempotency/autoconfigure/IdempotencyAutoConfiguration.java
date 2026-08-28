package io.github.benhendayoussef.idempotency.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.benhendayoussef.idempotency.api.IdempotencyMetrics;
import io.github.benhendayoussef.idempotency.api.IdempotencyObjectMapperCustomizer;
import io.github.benhendayoussef.idempotency.api.IdempotencyScope;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.api.NoScopeResolverConfiguredException;
import io.github.benhendayoussef.idempotency.api.ScopeResolver;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties;
import io.github.benhendayoussef.idempotency.internal.ArgumentFingerprinter;
import io.github.benhendayoussef.idempotency.internal.IdempotencyAspect;
import io.github.benhendayoussef.idempotency.internal.IdempotencyExceptionHandler;
import io.github.benhendayoussef.idempotency.internal.IdempotencyKeyComposer;
import io.github.benhendayoussef.idempotency.internal.InMemoryIdempotencyStore;
import io.github.benhendayoussef.idempotency.internal.filter.IdempotencyFilter;
import io.github.benhendayoussef.idempotency.internal.NoOpIdempotencyMetrics;
import io.github.benhendayoussef.idempotency.internal.TransactionRunner;
import io.github.benhendayoussef.idempotency.internal.scope.GlobalScopeResolver;
import io.github.benhendayoussef.idempotency.internal.scope.PrincipalScopeResolver;
import io.github.benhendayoussef.idempotency.internal.scope.TenantScopeResolver;
import io.github.benhendayoussef.idempotency.store.jdbc.internal.IdempotencyRecordSweeper;
import io.github.benhendayoussef.idempotency.store.jdbc.internal.IdempotencySweeperScheduler;
import io.github.benhendayoussef.idempotency.store.jdbc.internal.JdbcIdempotencyStore;
import io.github.benhendayoussef.idempotency.store.jdbc.internal.JdbcTransactionRunner;
import io.github.benhendayoussef.idempotency.store.redis.internal.RedisIdempotencyStore;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Auto-configures {@code @Idempotent} support: the aspect, key composition, fingerprinting, and
 * whichever {@link IdempotencyStore} the classpath and {@code idempotency.store} property select.
 * If nothing qualifies, {@link IdempotencyStoreFallbackAutoConfiguration} fails startup with an
 * actionable message instead of silently running unprotected.
 */
// Referenced by name, not by class literal: both are optional (compileOnly) and may not be on
// the classpath at all, unlike a hard `after = {Foo.class}` this doesn't fail attribute
// introspection when the referenced autoconfiguration is absent.
@AutoConfiguration(afterName = {
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
@ConditionalOnProperty(prefix = "idempotency", name = "enabled", matchIfMissing = true)
@ConditionalOnWebApplication(type = Type.SERVLET)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public static IdempotentMethodVisibilityValidator idempotentMethodVisibilityValidator() {
        return new IdempotentMethodVisibilityValidator();
    }

    @Bean
    @ConditionalOnMissingBean(name = "idempotencyPayloadObjectMapper")
    public ObjectMapper idempotencyPayloadObjectMapper(ObjectProvider<IdempotencyObjectMapperCustomizer> customizers) {
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        customizers.orderedStream().forEach(c -> c.customize(mapper));
        return mapper;
    }

    @Bean
    @ConditionalOnMissingBean
    public ArgumentFingerprinter idempotencyArgumentFingerprinter() {
        return new ArgumentFingerprinter();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyKeyComposer idempotencyKeyComposer() {
        return new IdempotencyKeyComposer();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyMetrics idempotencyMetrics() {
        return new NoOpIdempotencyMetrics();
    }

    @Bean(name = "globalScopeResolver")
    @ConditionalOnMissingBean(name = "globalScopeResolver")
    public ScopeResolver globalScopeResolver() {
        return new GlobalScopeResolver();
    }

    @Bean(name = "principalScopeResolver")
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    @ConditionalOnMissingBean(name = "principalScopeResolver")
    public ScopeResolver principalScopeResolver() {
        return new PrincipalScopeResolver();
    }

    @Bean(name = "tenantScopeResolver")
    @ConditionalOnClass(name = "org.springframework.security.oauth2.jwt.Jwt")
    @ConditionalOnMissingBean(name = "tenantScopeResolver")
    public ScopeResolver tenantScopeResolver(IdempotencyProperties props) {
        return new TenantScopeResolver(props.getTenantClaim());
    }

    @Bean
    @ConditionalOnMissingBean
    public Map<IdempotencyScope, ScopeResolver> idempotencyScopeResolvers(
            List<ScopeResolver> resolvers, IdempotencyProperties props) {
        Map<IdempotencyScope, ScopeResolver> map = new EnumMap<>(IdempotencyScope.class);
        resolvers.forEach(r -> map.put(r.supports(), r));

        // The configured scope is only actually consulted for GLOBAL as a special case (no
        // resolver needed); anything else must have a resolver registered, or every request that
        // supplies an idempotency key would fail unpredictably instead of at startup. This is the
        // same "fail loud now, not per-request later" shape as IdempotencyStoreFallbackAutoConfiguration.
        IdempotencyScope configuredScope = props.getScope();
        if (configuredScope != IdempotencyScope.GLOBAL && !map.containsKey(configuredScope)) {
            throw new NoScopeResolverConfiguredException(configuredScope);
        }
        return map;
    }

    // ASPECT is the default, and matchIfMissing keeps an application that never set idempotency.mode
    // on exactly the behaviour it had before the property existed.
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "idempotency", name = "mode", havingValue = "aspect", matchIfMissing = true)
    public IdempotencyAspect idempotencyAspect(IdempotencyStore store, IdempotencyProperties props,
            ArgumentFingerprinter fingerprinter, IdempotencyKeyComposer composer,
            Map<IdempotencyScope, ScopeResolver> scopes,
            ObjectMapper idempotencyPayloadObjectMapper, IdempotencyMetrics metrics,
            ObjectProvider<TransactionRunner> transactionRunner) {
        TransactionRunner runner = transactionRunner.getIfAvailable();
        if (runner == null && props.getJdbc().isJoinTransaction()) {
            // Reachable when join-transaction is set but the active store is not JDBC, so no runner
            // was contributed. A no-op rather than a startup failure: the property is namespaced
            // under idempotency.jdbc, and a shared configuration profile that sets it should not
            // break an application that deliberately runs on Redis or memory.
            log.warn("idempotency.jdbc.join-transaction=true has no effect with idempotency.store={} "
                    + "- transaction joining is only implemented for the JDBC store. Idempotency "
                    + "remains at-least-once.", props.getStore());
        }
        return new IdempotencyAspect(store, props, fingerprinter, composer, scopes,
                idempotencyPayloadObjectMapper, metrics, runner);
    }

    /**
     * Filter mode. Mutually exclusive with the aspect above by property value, so exactly one of the
     * two is ever registered - running both would have each claim the same key for the same request.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "idempotency", name = "mode", havingValue = "filter")
    public IdempotencyFilter idempotencyFilter(IdempotencyStore store, IdempotencyProperties props,
            ObjectMapper idempotencyPayloadObjectMapper, IdempotencyMetrics metrics,
            ObjectProvider<HandlerMapping> handlerMappings) {
        // ObjectProvider, not a direct List injection: the filter is created while the mapping beans
        // are still being built, and demanding them eagerly here deadlocks context startup.
        return new IdempotencyFilter(store, props, idempotencyPayloadObjectMapper, metrics,
                handlerMappings.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "idempotency", name = "problem-details", matchIfMissing = true)
    public IdempotencyExceptionHandler idempotencyExceptionHandler(IdempotencyProperties props) {
        return new IdempotencyExceptionHandler(props);
    }

    // Guards on RedisIdempotencyStore too, not just StringRedisTemplate: a user could have Spring
    // Data Redis on the classpath without having added the idempotency-store-redis dependency.
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ StringRedisTemplate.class, RedisIdempotencyStore.class })
    @ConditionalOnProperty(prefix = "idempotency", name = "store", havingValue = "redis", matchIfMissing = true)
    static class RedisStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(IdempotencyStore.class)
        @ConditionalOnBean(StringRedisTemplate.class)
        IdempotencyStore redisIdempotencyStore(StringRedisTemplate redisTemplate,
                ObjectMapper idempotencyPayloadObjectMapper, IdempotencyProperties props) {
            return new RedisIdempotencyStore(redisTemplate, idempotencyPayloadObjectMapper,
                    props.getRedis().getKeyPrefix());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ JdbcTemplate.class, JdbcIdempotencyStore.class })
    @ConditionalOnProperty(prefix = "idempotency", name = "store", havingValue = "jdbc")
    static class JdbcStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(IdempotencyStore.class)
        @ConditionalOnBean(DataSource.class)
        IdempotencyStore jdbcIdempotencyStore(DataSource dataSource, IdempotencyProperties props) {
            return new JdbcIdempotencyStore(new NamedParameterJdbcTemplate(dataSource), props.getJdbc().getTableName());
        }

        @Bean
        @ConditionalOnBean(DataSource.class)
        @ConditionalOnProperty(prefix = "idempotency.jdbc", name = "sweeper-enabled")
        IdempotencyRecordSweeper idempotencyRecordSweeper(DataSource dataSource, IdempotencyProperties props) {
            return new IdempotencyRecordSweeper(new NamedParameterJdbcTemplate(dataSource), props.getJdbc().getTableName());
        }

        @Bean
        @ConditionalOnBean(IdempotencyRecordSweeper.class)
        IdempotencySweeperScheduler idempotencySweeperScheduler(IdempotencyRecordSweeper sweeper, IdempotencyProperties props) {
            return new IdempotencySweeperScheduler(sweeper, props.getJdbc().getSweeperInterval());
        }

        /**
         * Present only when transaction joining is switched on. Its presence is what flips the
         * aspect from separate commits to a single shared one - the aspect itself has no property
         * check, it just asks whether a runner was wired.
         *
         * <p>The transaction manager is resolved through an {@link ObjectProvider} rather than
         * injected directly so a missing one produces a message naming the property that asked for
         * it, instead of Spring's generic "no qualifying bean of type PlatformTransactionManager".
         */
        @Bean
        @ConditionalOnBean(DataSource.class)
        @ConditionalOnProperty(prefix = "idempotency.jdbc", name = "join-transaction")
        TransactionRunner idempotencyTransactionRunner(ObjectProvider<PlatformTransactionManager> txManager) {
            PlatformTransactionManager manager = txManager.getIfUnique();
            if (manager == null) {
                throw new IllegalStateException(
                        "idempotency.jdbc.join-transaction=true requires exactly one "
                        + "PlatformTransactionManager bean, but none was found (or more than one was, "
                        + "with no @Primary). Transaction joining is what makes the JDBC store "
                        + "exactly-once; without a transaction manager it cannot work. Either add "
                        + "spring-boot-starter-jdbc (or another module that auto-configures one), "
                        + "mark the intended manager @Primary, or set "
                        + "idempotency.jdbc.join-transaction=false to keep the at-least-once default.");
            }
            return new JdbcTransactionRunner(manager);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "idempotency", name = "store", havingValue = "memory")
    static class MemoryStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(IdempotencyStore.class)
        IdempotencyStore inMemoryIdempotencyStore() {
            return new InMemoryIdempotencyStore();
        }
    }
}
