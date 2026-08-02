/**
 * The built-in {@code ScopeResolver} implementations ({@code GLOBAL}, {@code USER}, {@code TENANT}).
 * Public so a consumer can subclass or reference one directly, and because Spring auto-configuration
 * in a different module ({@code idempotency-spring-boot-starter}) constructs them - but this
 * package is <strong>not part of the supported API</strong> in the same sense as
 * {@link io.github.benhendayoussef.idempotency.api}: internal fields, constructors, and helper
 * methods here may change without notice. Register your own
 * {@link io.github.benhendayoussef.idempotency.api.ScopeResolver} bean instead of depending on
 * these classes' internals.
 */
package io.github.benhendayoussef.idempotency.internal.scope;
