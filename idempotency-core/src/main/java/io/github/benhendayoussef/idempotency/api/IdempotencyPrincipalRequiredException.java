package io.github.benhendayoussef.idempotency.api;

/**
 * Thrown when {@code idempotency.on-missing-principal=reject} and a {@code USER}/{@code TENANT}
 * -scoped request reaches the aspect without a resolvable authenticated principal. Maps to 400 -
 * never a raw {@code IllegalStateException} bubbling out of a {@link ScopeResolver}.
 */
public class IdempotencyPrincipalRequiredException extends RuntimeException {

    public IdempotencyPrincipalRequiredException(Throwable cause) {
        super("No authenticated principal available for scoped idempotency key", cause);
    }
}
