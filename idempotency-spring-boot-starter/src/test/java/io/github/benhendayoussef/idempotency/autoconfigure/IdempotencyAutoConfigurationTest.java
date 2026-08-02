package io.github.benhendayoussef.idempotency.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.api.ScopeResolver;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties;
import io.github.benhendayoussef.idempotency.internal.IdempotencyAspect;
import io.github.benhendayoussef.idempotency.store.jdbc.internal.JdbcIdempotencyStore;
import io.github.benhendayoussef.idempotency.store.redis.internal.RedisIdempotencyStore;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * The Step 8 slice test: asserts the store conditionals fire correctly with/without Redis (or a
 * DataSource) on the classpath, instead of the classic "my starter does nothing when the user
 * has a custom bean" failure mode.
 */
class IdempotencyAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    IdempotencyAutoConfiguration.class, IdempotencyStoreFallbackAutoConfiguration.class));

    @Test
    void backsOffWhenDisabled() {
        runner.withUserConfiguration(RedisTemplateConfiguration.class)
                .withPropertyValues("idempotency.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(IdempotencyAspect.class);
                    assertThat(ctx).doesNotHaveBean(IdempotencyStore.class);
                });
    }

    @Test
    void picksRedisWhenAStringRedisTemplateBeanExists() {
        runner.withUserConfiguration(RedisTemplateConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(IdempotencyStore.class);
                    assertThat(ctx.getBean(IdempotencyStore.class)).isInstanceOf(RedisIdempotencyStore.class);
                    assertThat(ctx).hasSingleBean(IdempotencyAspect.class);
                });
    }

    @Test
    void picksJdbcWhenExplicitlyConfiguredWithADataSource() {
        runner.withUserConfiguration(DataSourceConfiguration.class)
                .withPropertyValues("idempotency.store=jdbc")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(IdempotencyStore.class);
                    assertThat(ctx.getBean(IdempotencyStore.class)).isInstanceOf(JdbcIdempotencyStore.class);
                });
    }

    @Test
    void picksInMemoryWhenExplicitlyConfigured() {
        runner.withPropertyValues("idempotency.store=memory")
                .run(ctx -> assertThat(ctx).hasSingleBean(IdempotencyStore.class));
    }

    @Test
    void registersTheNonPublicMethodVisibilityValidatorByDefault() {
        runner.withUserConfiguration(RedisTemplateConfiguration.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(IdempotentMethodVisibilityValidator.class));
    }

    @Test
    void failsStartupWithAnActionableMessageWhenNoStoreCanBeConfigured() {
        // No StringRedisTemplate/DataSource beans and store left at AUTO: nothing qualifies.
        runner.withClassLoader(new FilteredClassLoader(StringRedisTemplate.class, JdbcTemplate.class))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    // Must be the actionable FailureAnalyzer message, not a raw NoSuchBeanDefinitionException
                    // bubbling up from the aspect's IdempotencyStore constructor argument.
                    assertThat(ctx.getStartupFailure()).hasMessageContaining("No IdempotencyStore is configured");
                    assertThat(ctx.getStartupFailure()).hasMessageNotContaining("NoSuchBeanDefinitionException");
                });
    }

    @Test
    void failsStartupWithAnActionableMessageWhenScopeUserIsSetButHasNoResolver() {
        // idempotency.scope defaults to GLOBAL, which needs no ScopeResolver at all, so this
        // failure only reproduces when a user explicitly opts into USER scope without Spring
        // Security on the classpath - the shape of a consumer who added spring-boot-starter-web
        // plus spring-boot-starter-data-redis and set scope=user by hand.
        runner.withUserConfiguration(RedisTemplateConfiguration.class)
                .withPropertyValues("idempotency.scope=user")
                .withClassLoader(new FilteredClassLoader(
                        org.springframework.security.core.context.SecurityContextHolder.class))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasMessageContaining("No ScopeResolver is configured for idempotency.scope=user");
                });
    }

    @Test
    void defaultScopeIsGlobalSoStartupNeedsNoSpringSecurity() {
        // A context with only web + a store, Spring Security genuinely absent from the classpath
        // (not merely unconfigured), and zero idempotency.* properties must boot cleanly - GLOBAL
        // never consults a ScopeResolver at all, since IdempotencyAspect.resolveNamespace
        // short-circuits before reaching the resolver map.
        runner.withUserConfiguration(RedisTemplateConfiguration.class)
                .withClassLoader(new FilteredClassLoader(
                        org.springframework.security.core.context.SecurityContextHolder.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(IdempotencyAspect.class);
                    assertThat(ctx).hasSingleBean(IdempotencyStore.class);
                });
    }

    @Test
    void userSuppliedStoreBeanWins() {
        runner.withUserConfiguration(RedisTemplateConfiguration.class, CustomStoreConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(IdempotencyStore.class);
                    assertThat(ctx.getBean(IdempotencyStore.class)).isSameAs(CustomStoreConfiguration.CUSTOM_STORE);
                });
    }

    @Test
    void userSuppliedScopeResolverWins() {
        runner.withUserConfiguration(RedisTemplateConfiguration.class, CustomGlobalScopeResolverConfiguration.class)
                .run(ctx -> assertThat(ctx.getBean("globalScopeResolver", ScopeResolver.class))
                        .isSameAs(CustomGlobalScopeResolverConfiguration.CUSTOM_RESOLVER));
    }

    @Test
    void nonWebApplicationContextBacksOffCleanly() {
        // IdempotencyAutoConfiguration is @ConditionalOnWebApplication(SERVLET); a plain (non-web)
        // context must not fail and must not register the aspect or a store.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        IdempotencyAutoConfiguration.class, IdempotencyStoreFallbackAutoConfiguration.class))
                .withUserConfiguration(RedisTemplateConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(IdempotencyAspect.class);
                    assertThat(ctx).doesNotHaveBean(IdempotencyStore.class);
                });
    }

    @Test
    void customRedisTemplateWithoutAStringRedisTemplateBeanStillPicksUpTheRedisStore() {
        // The classic real-world break: a user who already has a RedisTemplate<String, Object>
        // bean of their own (not a StringRedisTemplate) but never touched idempotency config.
        // Spring Boot's own DataRedisAutoConfiguration is @ConditionalOnMissingBean(StringRedisTemplate.class)
        // (not gated on any *other* RedisTemplate bean existing), so it still supplies a
        // StringRedisTemplate as long as a RedisConnectionFactory exists - verified here against
        // the real autoconfiguration, not a hand-rolled stand-in.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class,
                        IdempotencyAutoConfiguration.class, IdempotencyStoreFallbackAutoConfiguration.class))
                .withUserConfiguration(RedisConnectionFactoryOnlyConfiguration.class, CustomNonStringRedisTemplateConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(StringRedisTemplate.class);
                    assertThat(ctx).hasSingleBean(IdempotencyStore.class);
                    assertThat(ctx.getBean(IdempotencyStore.class)).isInstanceOf(RedisIdempotencyStore.class);
                });
    }

    @Test
    void releaseOnBindsFromTheDocumentedRelaxedValues() {
        runner.withUserConfiguration(RedisTemplateConfiguration.class)
                .withPropertyValues("idempotency.release-on=five_xx,timeout")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var props = ctx.getBean(IdempotencyProperties.class);
                    assertThat(props.getReleaseOn())
                            .containsExactlyInAnyOrder(IdempotencyProperties.ReleaseOn.FIVE_XX,
                                    IdempotencyProperties.ReleaseOn.TIMEOUT);
                });
    }

    @Test
    void autoConfigurationImportsResourceReferencesRealLoadableClasses() throws IOException, ClassNotFoundException {
        // Load the resource and Class.forName each line - a typo here is invisible to a plain
        // file read, since the file would still "look" correct.
        URL resource = getClass().getClassLoader()
                .getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(resource).isNotNull();

        List<String> lines;
        try (InputStream in = resource.openStream()) {
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }

        assertThat(lines).isNotEmpty();
        for (String fqn : lines) {
            assertThat(Class.forName(fqn, false, getClass().getClassLoader()))
                    .as("AutoConfiguration.imports entry '%s' must resolve to a real class", fqn)
                    .isNotNull();
        }
        assertThat(lines).containsExactlyInAnyOrder(
                "io.github.benhendayoussef.idempotency.autoconfigure.IdempotencyAutoConfiguration",
                "io.github.benhendayoussef.idempotency.autoconfigure.IdempotencyStoreFallbackAutoConfiguration");
    }

    @Test
    void configurationMetadataJsonIsPackagedInTheBuiltJar() throws IOException {
        // IdempotencyProperties (the @ConfigurationProperties class) is compiled in
        // idempotency-core, so that is where the annotation processor's output actually lands -
        // not the starter jar, even though the starter is what most users think of as "the config."
        String jarPathProperty = System.getProperty("coreJarPath");
        assertThat(jarPathProperty)
                .as("coreJarPath system property must be injected by the Gradle test task")
                .isNotBlank();
        Path jarPath = Path.of(jarPathProperty);
        assertThat(Files.exists(jarPath)).as("jar must exist at %s - run the jar task first", jarPath).isTrue();

        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/spring-configuration-metadata.json");
            assertThat(entry).as("spring-configuration-metadata.json must be packaged in the jar").isNotNull();

            String content = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).contains("\"idempotency.enabled\"");
            assertThat(content).contains("\"idempotency.store\"");
            assertThat(content).contains("\"idempotency.default-ttl\"");
            // The auto-generated properties list and the hand-written hints/descriptions in
            // additional-spring-configuration-metadata.json only merge if processResources runs
            // before compileJava (see the dependsOn wiring in idempotency-core/build.gradle.kts) -
            // assert the merge actually happened, not just that some metadata file exists.
            assertThat(content).as("hand-written hints must be merged into the generated metadata")
                    .contains("\"fail_fast\"")
                    .contains("Poll until the in-flight request completes");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RedisTemplateConfiguration {
        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            return org.mockito.Mockito.mock(RedisConnectionFactory.class);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DataSourceConfiguration {
        @Bean
        DataSource dataSource() {
            var ds = new SimpleDriverDataSource();
            ds.setUrl("jdbc:postgresql://localhost:5432/test");
            return ds;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomStoreConfiguration {
        static final IdempotencyStore CUSTOM_STORE = org.mockito.Mockito.mock(IdempotencyStore.class);

        @Bean
        IdempotencyStore idempotencyStore() {
            return CUSTOM_STORE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomGlobalScopeResolverConfiguration {
        static final ScopeResolver CUSTOM_RESOLVER = org.mockito.Mockito.mock(ScopeResolver.class);
        static {
            org.mockito.Mockito.when(CUSTOM_RESOLVER.supports()).thenReturn(io.github.benhendayoussef.idempotency.api.IdempotencyScope.GLOBAL);
        }

        @Bean(name = "globalScopeResolver")
        ScopeResolver globalScopeResolver() {
            return CUSTOM_RESOLVER;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RedisConnectionFactoryOnlyConfiguration {
        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            return org.mockito.Mockito.mock(RedisConnectionFactory.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomNonStringRedisTemplateConfiguration {
        @Bean
        RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
            var template = new RedisTemplate<String, Object>();
            template.setConnectionFactory(factory);
            template.afterPropertiesSet();
            return template;
        }
    }
}
