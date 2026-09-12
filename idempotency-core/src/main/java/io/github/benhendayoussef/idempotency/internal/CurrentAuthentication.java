package io.github.benhendayoussef.idempotency.internal;

import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Reads the servlet stack's current authentication out of Spring Security's ThreadLocal.
 *
 * <p><strong>This class must only be loaded when a scoped key is actually being resolved.</strong>
 * Spring Security is {@code compileOnly} for this module, so referencing it from a code path that a
 * {@code scope=global} application reaches would throw {@code NoClassDefFoundError} in every
 * application that never asked for per-caller keys. {@code IdempotencyAspect.resolveNamespace}
 * returns before touching this for {@code GLOBAL}, which keeps the class unloaded there - the same
 * laziness the built-in resolvers have always depended on.
 *
 * <p>Not part of the supported API; see the package documentation.
 */
final class CurrentAuthentication {

    private CurrentAuthentication() {
    }

    /** Empty when nobody is authenticated, or when no security context exists for this thread. */
    static Optional<Object> get() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication());
    }
}
