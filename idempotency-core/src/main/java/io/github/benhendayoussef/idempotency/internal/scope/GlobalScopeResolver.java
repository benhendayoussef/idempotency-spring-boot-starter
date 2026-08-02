package io.github.benhendayoussef.idempotency.internal.scope;

import io.github.benhendayoussef.idempotency.api.IdempotencyScope;
import io.github.benhendayoussef.idempotency.api.ScopeResolver;

/** No namespace: every caller shares the same keyspace for a given route. */
public class GlobalScopeResolver implements ScopeResolver {

    @Override
    public IdempotencyScope supports() {
        return IdempotencyScope.GLOBAL;
    }

    @Override
    public String namespace() {
        return "";
    }
}
