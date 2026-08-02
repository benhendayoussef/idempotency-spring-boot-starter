package io.github.benhendayoussef.idempotency.internal.scope;

import io.github.benhendayoussef.idempotency.api.IdempotencyScope;
import io.github.benhendayoussef.idempotency.api.ScopeResolver;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Namespaces by a configurable JWT claim (default {@code tenant_id}). Only usable when the
 * current authentication's principal is a {@link Jwt}, e.g. behind Spring Security's resource
 * server support.
 */
public class TenantScopeResolver implements ScopeResolver {

    private final String claimName;

    public TenantScopeResolver(String claimName) {
        this.claimName = claimName;
    }

    @Override
    public IdempotencyScope supports() {
        return IdempotencyScope.TENANT;
    }

    @Override
    public String namespace() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof Jwt jwt)) {
            throw new IllegalStateException("No JWT principal for TENANT-scoped idempotency key");
        }
        String tenant = jwt.getClaimAsString(claimName);
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalStateException("JWT is missing claim '" + claimName + "' for TENANT-scoped idempotency key");
        }
        return tenant;
    }
}
