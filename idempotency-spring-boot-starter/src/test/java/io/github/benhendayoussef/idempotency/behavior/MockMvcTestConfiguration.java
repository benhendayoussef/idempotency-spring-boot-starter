package io.github.benhendayoussef.idempotency.behavior;

import jakarta.servlet.Filter;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Supplies the {@link MockMvc} bean that {@code @AutoConfigureMockMvc} used to provide.
 *
 * <p>Boot 4 moved that annotation into its own {@code spring-boot-webmvc-test} module, which does
 * not exist in Boot 3 - it was the <strong>single</strong> thing preventing this test suite from
 * compiling against both generations. Building {@code MockMvc} by hand costs a few lines and
 * removes the only version-specific dependency in the project, so the same sources run on Boot 3.x
 * and 4.x with no source sets, no reflection, and no duplicated tests.
 *
 * <p>Everything here comes from {@code spring-test} and {@code jakarta.servlet}, both of which are
 * identical across the two generations.
 */
@Configuration(proxyBeanMethods = false)
class MockMvcTestConfiguration {

    @Bean
    MockMvc mockMvc(WebApplicationContext context, ObjectProvider<Filter> filters) {
        var builder = MockMvcBuilders.webAppContextSetup(context);

        // Registering the context's servlet filters is not incidental: SpringSecurityOrderingTest
        // asserts that an unauthenticated request is rejected with 401 by the security filter chain
        // *before* it ever reaches the dispatcher and the aspect. Without the filters attached that
        // test would pass for the wrong reason - the request would sail through to the handler.
        // orderedStream() honours @Order/Ordered, so the chain keeps its real relative ordering.
        List<Filter> registered = filters.orderedStream().toList();
        if (!registered.isEmpty()) {
            builder.addFilters(registered.toArray(new Filter[0]));
        }
        return builder.build();
    }
}
