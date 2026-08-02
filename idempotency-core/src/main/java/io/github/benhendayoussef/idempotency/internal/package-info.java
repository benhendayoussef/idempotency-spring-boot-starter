/**
 * The aspect, key composition/fingerprinting, and default store/metrics implementations. Several
 * types here are public only because Spring auto-configuration in a different module
 * ({@code idempotency-spring-boot-starter}) constructs or references them directly - this package
 * is <strong>not part of the supported API</strong> and may change without notice between any two
 * releases, including patch releases. Consumers should depend on
 * {@link io.github.benhendayoussef.idempotency.api} and {@link io.github.benhendayoussef.idempotency.config.IdempotencyProperties} instead.
 */
package io.github.benhendayoussef.idempotency.internal;
