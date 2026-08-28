package io.github.benhendayoussef.idempotency.webflux.internal;

import io.github.benhendayoussef.idempotency.internal.Hashing;
import java.util.Objects;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;

/**
 * The WebFlux counterpart to {@code IdempotencyKeyComposer}.
 *
 * <p>Produces byte-identical keys to the servlet version for the same request, deliberately: the two
 * stacks can share a store - a service migrating from MVC to WebFlux one endpoint at a time has
 * both running against the same Redis - and a key that changed shape mid-migration would silently
 * stop deduplicating exactly when it mattered.
 *
 * <p>The route pattern, not the raw URI, is what goes into the hash. Otherwise {@code /orders/1} and
 * {@code /orders/2} would be different routes to the deduplication logic even though they are the
 * same endpoint, and a client reusing one key across them would not be protected.
 */
final class ReactiveKeyComposer {

    private ReactiveKeyComposer() {
    }

    static String compose(String clientKey, String namespace, ServerWebExchange exchange) {
        // BEST_MATCHING_PATTERN_ATTRIBUTE holds a PathPattern here rather than the String the
        // servlet stack stores; toString() gives the same pattern text either way.
        Object pattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = Objects.toString(pattern, exchange.getRequest().getPath().value());
        String method = exchange.getRequest().getMethod().name();
        return Hashing.sha256Hex(namespace + '|' + method + '|' + route + '|' + clientKey);
    }
}
