package io.github.benhendayoussef.idempotency.api;

/**
 * Resolves the namespace component of a storage key for a given {@link IdempotencyScope}.
 * Register a bean implementing this to override one of the built-in resolvers
 * (global, principal, tenant) — {@code @ConditionalOnMissingBean} lets a user-supplied bean win.
 *
 * <p>The namespace keeps unrelated callers from colliding on the same client-supplied key: user A's
 * {@code "abc-123"} and user B's {@code "abc-123"} are different storage keys, so one caller can
 * never replay another's response.
 */
public interface ScopeResolver {

    /** Which scope this resolver answers for. One resolver per scope. */
    IdempotencyScope supports();

    /**
     * Returns the namespace for this request, or throws {@link IllegalStateException} if it cannot
     * be resolved — typically because the caller is not authenticated.
     *
     * <p>An {@link IllegalStateException} from here is a <em>per-request</em> condition, not a
     * wiring error, and the library treats it as one: it is routed through
     * {@code idempotency.on-missing-principal}, which decides whether the request falls back to the
     * global namespace, executes unprotected, or is rejected with a 400. Throwing any other type
     * lets the exception escape to the caller, so throw {@code IllegalStateException} to opt into
     * that policy.
     *
     * <p><strong>Changed in 1.0</strong>: this took no arguments before, and implementations read
     * {@code SecurityContextHolder} directly. Everything a resolver needs now arrives in
     * {@code context} — see {@link IdempotencyContext} for why.
     */
    String namespace(IdempotencyContext context);
}
