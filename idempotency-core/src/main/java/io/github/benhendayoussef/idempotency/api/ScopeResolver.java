package io.github.benhendayoussef.idempotency.api;

/**
 * Resolves the namespace component of a storage key for a given {@link IdempotencyScope}.
 * Register a bean implementing this to override one of the built-in resolvers
 * (global, principal, tenant) — {@code @ConditionalOnMissingBean} lets a user-supplied bean win.
 */
public interface ScopeResolver {

    IdempotencyScope supports();

    /** May throw if the namespace can't be resolved for the current request (e.g. no auth). */
    String namespace();
}
