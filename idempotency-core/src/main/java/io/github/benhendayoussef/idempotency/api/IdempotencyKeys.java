package io.github.benhendayoussef.idempotency.api;

import io.github.benhendayoussef.idempotency.internal.Hashing;

/**
 * Derives the storage key that {@link IdempotencyStore} is addressed by.
 *
 * <p>{@code IdempotencyStore#find} and {@code IdempotencyStore#release} have always been public, but
 * until 0.4 the key they take could only be computed by internal code - which made both methods
 * effectively unusable from outside the library. That mattered most in exactly the situation you
 * would want them: a process died mid-request, its key is stuck {@code IN_PROGRESS}, and you want to
 * look at it or clear it.
 *
 * <p>The client never sees this value and it is never sent over the wire. The header the client
 * supplies is hashed together with everything needed to keep unrelated callers, routes and
 * namespaces from colliding on it:
 *
 * <pre>{@code sha256( namespace | httpMethod | routePattern | clientKey )}</pre>
 *
 * <p>Two consequences worth understanding before using this:
 *
 * <ul>
 *   <li><strong>The route pattern, not the request URI.</strong> {@code /orders/{id}} rather than
 *       {@code /orders/42} - otherwise every path variable would be a different route and a client
 *       reusing one key across them would not be protected.</li>
 *   <li><strong>The namespace depends on the scope.</strong> Empty for {@code GLOBAL}, the principal
 *       name for {@code USER}, the tenant claim for {@code TENANT}. Passing the wrong one produces a
 *       valid-looking key that addresses nothing.</li>
 * </ul>
 *
 * <p>This is also the single definition of the key format. The servlet, reactive and filter paths
 * all route through it, so a key means the same thing on every stack - which is what lets a service
 * migrating from MVC to WebFlux share one store without silently losing deduplication.
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    /**
     * The storage key for a client key in the global namespace - the default scope.
     *
     * @param clientKey    the value the caller sent in the {@code Idempotency-Key} header
     * @param httpMethod   the request method, e.g. {@code POST}
     * @param routePattern the mapped route pattern, e.g. {@code /orders/{id}}
     */
    public static String storageKey(String clientKey, String httpMethod, String routePattern) {
        return storageKey(clientKey, httpMethod, routePattern, "");
    }

    /**
     * The storage key for a client key in an explicit namespace.
     *
     * @param namespace the scope namespace: empty for {@code GLOBAL}, the principal name for
     *                  {@code USER}, the tenant claim value for {@code TENANT}
     */
    public static String storageKey(String clientKey, String httpMethod, String routePattern,
            String namespace) {
        if (clientKey == null || clientKey.isBlank()) {
            throw new IllegalArgumentException("clientKey must not be null or blank");
        }
        if (httpMethod == null || httpMethod.isBlank()) {
            throw new IllegalArgumentException("httpMethod must not be null or blank");
        }
        if (routePattern == null || routePattern.isBlank()) {
            throw new IllegalArgumentException("routePattern must not be null or blank");
        }
        String ns = namespace == null ? "" : namespace;
        return Hashing.sha256Hex(ns + '|' + httpMethod + '|' + routePattern + '|' + clientKey);
    }
}
