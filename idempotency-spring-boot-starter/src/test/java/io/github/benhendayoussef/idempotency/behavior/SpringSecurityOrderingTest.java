package io.github.benhendayoussef.idempotency.behavior;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.benhendayoussef.idempotency.api.Idempotent;
import io.github.benhendayoussef.idempotency.api.IdempotencyScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves {@code scope=USER} works through a <em>real</em> Spring Security filter chain (not the
 * hand-set {@code SecurityContextHolder} used elsewhere for speed/simplicity), and that the
 * principal the aspect resolves the namespace from is the actual authenticated user, never
 * {@code anonymousUser} or empty.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {SpringSecurityOrderingTest.TestApp.class, SpringSecurityOrderingTest.SecurityConfig.class,
                SpringSecurityOrderingTest.SecuredController.class},
        properties = {"idempotency.store=memory",
                // Shared constant so this names both Boot generations - see TestAutoconfigExcludes.
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE})
@Import(MockMvcTestConfiguration.class)
class SpringSecurityOrderingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void userScopedEndpointSeesTheRealAuthenticatedPrincipalNotAnonymous() throws Exception {
        mockMvc.perform(get("/secured").with(httpBasic("alice", "pw"))
                        .header("Idempotency-Key", "k-alice"))
                .andExpect(status().isOk())
                .andExpect(content().string("alice"));
    }

    @Test
    void unauthenticatedRequestIsRejectedBeforeReachingTheAspect() throws Exception {
        // No credentials: Spring Security's filter chain itself returns 401 before the request
        // ever reaches DispatcherServlet/the AOP proxy - proving Security runs first, as it must
        // (a Servlet Filter always runs ahead of the MVC handler dispatch the AOP proxy wraps).
        mockMvc.perform(get("/secured").header("Idempotency-Key", "k-anon"))
                .andExpect(status().isUnauthorized());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class SecurityConfig {
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .csrf(csrf -> csrf.disable());
            return http.build();
        }

        @Bean
        InMemoryUserDetailsManager userDetailsService() {
            return new InMemoryUserDetailsManager(
                    User.withUsername("alice").password("{noop}pw").roles("USER").build());
        }
    }

    @RestController
    static class SecuredController {
        @Idempotent(scope = IdempotencyScope.USER)
        @GetMapping("/secured")
        ResponseEntity<String> whoAmI() {
            return ResponseEntity.ok(SecurityContextHolder.getContext().getAuthentication().getName());
        }
    }
}
