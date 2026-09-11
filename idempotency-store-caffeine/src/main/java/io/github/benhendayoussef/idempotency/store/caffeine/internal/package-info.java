/**
 * The Caffeine-backed {@code IdempotencyStore} implementation. Public only because Spring
 * auto-configuration in a different module ({@code idempotency-spring-boot-starter}) constructs it
 * directly - none of these types are <strong>part of the supported API</strong> and may change or
 * move without notice between any two releases, including patch releases. Consumers should depend
 * on {@link io.github.benhendayoussef.idempotency.api.IdempotencyStore} instead.
 */
package io.github.benhendayoussef.idempotency.store.caffeine.internal;
