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
        String route = Objects.toString(
                request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),
                request.getRequestURI());
        return IdempotencyKeys.storageKey(clientKey, request.getMethod(), route, namespace);
    }
}
