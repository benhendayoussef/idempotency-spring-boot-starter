package io.github.benhendayoussef.idempotency.internal.scope;

import io.github.benhendayoussef.idempotency.api.IdempotencyScope;
import io.github.benhendayoussef.idempotency.api.ScopeResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Namespaces by the authenticated principal's name, so user A's key {@code "abc-123"} can never
 * collide with user B's. Only usable when Spring Security is on the classpath.
 */
public class PrincipalScopeResolver implements ScopeResolver {

    @Override
    public IdempotencyScope supports() {
        return IdempotencyScope.USER;
    }

    @Override
    public String namespace() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalStateException("No authenticated principal for USER-scoped idempotency key");
        }
        return auth.getName();
    }
}
