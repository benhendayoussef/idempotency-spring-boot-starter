package io.github.benhendayoussef.idempotency.api;

/**
 * Namespaces a client-supplied idempotency key so that unrelated callers can't collide on it.
 * {@code DEFAULT} falls through to the globally configured scope.
 */
public enum IdempotencyScope {
    DEFAULT,
    GLOBAL,
    USER,
    TENANT,
    CUSTOM
}
