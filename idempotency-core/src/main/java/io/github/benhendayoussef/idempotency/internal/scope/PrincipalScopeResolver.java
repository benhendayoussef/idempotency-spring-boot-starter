package io.github.benhendayoussef.idempotency.internal.scope;

import io.github.benhendayoussef.idempotency.api.IdempotencyContext;
import io.github.benhendayoussef.idempotency.api.IdempotencyScope;
import io.github.benhendayoussef.idempotency.api.ScopeResolver;
import org.springframework.security.core.Authentication;

/**
 * Namespaces by the authenticated principal's name, so user A's key {@code "abc-123"} can never
 * collide with user B's. Only usable when Spring Security is on the classpath.
 *
 * <p>Takes the authentication from the context rather than {@code SecurityContextHolder}. Same
 * result on the servlet stack, where the library populates it from exactly that ThreadLocal - but
 * the dependency now points one way, so a stack that carries its principal somewhere else can
 * supply it without this class changing.
 */
public class PrincipalScopeResolver implements ScopeResolver {

    @Override
    public IdempotencyScope supports() {
        return IdempotencyScope.USER;
    }

    @Override
    public String namespace(IdempotencyContext context) {
        Object auth = context.authentication().orElse(null);
        if (!(auth instanceof Authentication authentication) || !authentication.isAuthenticated()) {
            throw new IllegalStateException("No authenticated principal for USER-scoped idempotency key");
        }
        return authentication.getName();
    }
}
