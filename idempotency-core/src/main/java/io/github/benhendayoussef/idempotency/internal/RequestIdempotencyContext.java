package io.github.benhendayoussef.idempotency.internal;

import io.github.benhendayoussef.idempotency.api.IdempotencyContext;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The library's {@link IdempotencyContext}, built once per request that needs a scoped key.
 *
 * <p>Authentication arrives as a {@link Supplier} rather than a resolved value so that nothing
 * touches the security context unless a resolver actually asks for it. That matters for more than
 * speed: on a stack where resolving the principal is expensive - or, on a reactive stack, where it
 * is a {@code Mono} that has to be subscribed - eagerly resolving it for every request would make
 * the cheap resolvers pay for the expensive ones.
 *
 * <p>Not part of the supported API; see the package documentation.
 */
public record RequestIdempotencyContext(
        String clientKey,
        String httpMethod,
        String routePattern,
        Supplier<Optional<Object>> authenticationSupplier) implements IdempotencyContext {

    @Override
    public Optional<Object> authentication() {
        return authenticationSupplier.get();
    }
}
