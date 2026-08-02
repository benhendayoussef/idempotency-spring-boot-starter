package io.github.benhendayoussef.idempotency.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as safe to call twice: duplicate requests carrying the same
 * {@code Idempotency-Key} (or the configured header) get the original response replayed
 * instead of a re-execution.
 *
 * <p>See the SPI in {@link IdempotencyStore} for how replay is persisted, and
 * {@link IdempotencyScope} for how keys are namespaced across callers.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /** Duration string, e.g. "24h", "PT30M". Empty = use configured default. */
    String ttl() default "";

    /** Header carrying the client key. Empty = use configured default. */
    String keyHeader() default "";

    /** SpEL producing the key, evaluated against method args. Overrides keyHeader. */
    String key() default "";

    IdempotencyScope scope() default IdempotencyScope.DEFAULT;

    ConflictPolicy onConflict() default ConflictPolicy.DEFAULT;

    /** Reject a reused key carrying a different payload with 422. */
    boolean fingerprintArgs() default true;
}
