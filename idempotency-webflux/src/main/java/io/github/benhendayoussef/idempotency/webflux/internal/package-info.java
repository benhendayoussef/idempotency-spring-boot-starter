/**
 * The reactive aspect and the {@code WebFilter} that puts the {@code ServerWebExchange} into the
 * Reactor context. Public only because Spring auto-configuration in a different module
 * ({@code idempotency-spring-boot-starter}) constructs them directly - none of these types are
 * <strong>part of the supported API</strong> and may change or move without notice between any two
 * releases, including patch releases. Consumers should depend on
 * {@link io.github.benhendayoussef.idempotency.api} and the {@code @Idempotent} annotation instead.
 */
package io.github.benhendayoussef.idempotency.webflux.internal;
