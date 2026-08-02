/**
 * The JDBC/Postgres {@code IdempotencyStore} implementation and its optional expired-row sweeper.
 * Public only because Spring auto-configuration in a different module
 * ({@code idempotency-spring-boot-starter}) constructs them directly - none of these types are
 * <strong>part of the supported API</strong> and may change or move without notice between any two
 * releases, including patch releases. Consumers should depend on
 * {@link io.github.benhendayoussef.idempotency.api.IdempotencyStore} instead.
 */
package io.github.benhendayoussef.idempotency.store.jdbc.internal;
