package io.github.benhendayoussef.idempotency.api;

/**
 * Thrown at startup when {@code idempotency.scope} is set to a scope (directly or via its
 * default) that has no matching {@link ScopeResolver} registered - most commonly the default
 * {@code USER} scope with Spring Security absent from the classpath. Without this check, the
 * failure would instead surface unpredictably per-request, the first time a caller actually sends
 * an idempotency key.
 */
public class NoScopeResolverConfiguredException extends RuntimeException {

    public NoScopeResolverConfiguredException(IdempotencyScope scope) {
        super("No ScopeResolver is configured for idempotency.scope=" + scope.name().toLowerCase());
    }
}
