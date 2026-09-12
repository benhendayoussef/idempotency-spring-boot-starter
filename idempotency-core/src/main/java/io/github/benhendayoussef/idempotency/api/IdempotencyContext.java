package io.github.benhendayoussef.idempotency.api;

import java.util.Optional;

/**
 * What the library knows about the request it is currently deciding a key for.
 *
 * <p>Passed to {@link ScopeResolver#namespace(IdempotencyContext)} so a resolver never has to read
 * ambient state to do its job. Before 1.0 it did: {@code namespace()} took no arguments and reached
 * into {@code SecurityContextHolder}, a ThreadLocal. That worked on the servlet stack and nowhere
 * else, which is the whole reason {@code idempotency.scope} is restricted to {@code global} on
 * WebFlux - the ThreadLocal is empty there, and falling back to global silently would have shared
 * keys across users. Handing the resolver its inputs makes the SPI stack-agnostic, and makes it
 * testable without standing up a security context.
 *
 * <p><strong>This is an interface, not a record, deliberately.</strong> Records cannot gain
 * components compatibly, so a record here could never carry anything new without a major version.
 * New accessors can be added to this interface with default bodies as the library learns what
 * resolvers need.
 *
 * <p>Implementations are supplied by the library. Do not implement this yourself - new methods may
 * appear in any minor release, and only the library can populate them correctly.
 */
public interface IdempotencyContext {

    /** The raw key the client sent, before hashing - never blank. */
    String clientKey();

    /** The request's HTTP method, uppercase, e.g. {@code POST}. */
    String httpMethod();

    /**
     * The matched route pattern (e.g. {@code /orders/{id}}), falling back to the request URI when no
     * pattern matched. Two callers hitting the same route share a keyspace; different routes do not.
     */
    String routePattern();

    /**
     * The caller's authentication, when the stack has one and it has been resolved for this request.
     *
     * <p>Deliberately {@code Object} rather than Spring Security's {@code Authentication}: this
     * package is the supported API and must not drag Spring Security onto the compile classpath of
     * anyone who never uses a scoped key. Resolvers that want it should pattern-match, exactly as
     * the built-in {@code USER} and {@code TENANT} resolvers do.
     *
     * <p>Empty means the library could not resolve a principal for this request - an anonymous
     * caller, no security on the classpath, or a stack that does not populate it yet. A resolver
     * that requires one should throw {@link IllegalStateException}; see
     * {@link ScopeResolver#namespace(IdempotencyContext)} for what the library does with that.
     */
    Optional<Object> authentication();
}
