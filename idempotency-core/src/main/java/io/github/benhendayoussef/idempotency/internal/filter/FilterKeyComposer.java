package io.github.benhendayoussef.idempotency.internal.filter;

import io.github.benhendayoussef.idempotency.api.IdempotencyKeys;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Storage-key composition for filter mode.
 *
 * <p>Produces byte-identical keys to the aspect-mode composer for the same request. That is not
 * incidental: switching {@code idempotency.mode} must not orphan every key already in the store, and
 * an application running both modes across a rolling deploy has to agree with itself about what a
 * key means.
 */
final class FilterKeyComposer {

    private FilterKeyComposer() {
    }

    static String compose(String clientKey, String namespace, HttpServletRequest request) {
        // The mapping lookup this filter already performed populates BEST_MATCHING_PATTERN, so the
        // route pattern is available here exactly as the aspect sees it later.
        String route = Objects.toString(
                request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),
                request.getRequestURI());
        return IdempotencyKeys.storageKey(clientKey, request.getMethod(), route, namespace);
    }
}
