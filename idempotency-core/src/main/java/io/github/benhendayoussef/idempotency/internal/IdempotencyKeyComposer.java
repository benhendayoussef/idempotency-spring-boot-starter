package io.github.benhendayoussef.idempotency.internal;

import io.github.benhendayoussef.idempotency.api.IdempotencyKeys;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Composes the opaque storage key from the client-supplied key plus everything needed to keep
 * unrelated callers, routes, and namespaces from colliding on it. The client's header value is
 * never used as the storage key directly.
 */
public class IdempotencyKeyComposer {

    public String compose(String clientKey, String namespace, HttpServletRequest request) {
        return IdempotencyKeys.storageKey(clientKey, request.getMethod(), routePattern(request), namespace);
    }

    /**
     * The matched route pattern, falling back to the URI when nothing matched.
     *
     * <p>Static and shared because the scope context reports the same value to resolvers that the
     * key is built from. Two definitions of "which route is this" that drifted apart would namespace
     * a key by one route and store it under another.
     */
    public static String routePattern(HttpServletRequest request) {
        return Objects.toString(
                request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),
                request.getRequestURI());
    }
}
