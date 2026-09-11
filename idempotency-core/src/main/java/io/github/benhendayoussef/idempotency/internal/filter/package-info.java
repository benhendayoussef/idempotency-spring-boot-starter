/**
 * The {@code idempotency.mode=filter} implementation: the servlet filter that claims, replays and
 * completes at the HTTP layer, and the captured-response record it stores.
 *
 * <p>A {@code package-info} of its own because Java does not inherit one from a parent package -
 * the disclaimer on {@code ..idempotency.internal} does not reach this subpackage. These types are
 * <strong>not part of the supported API</strong> and may change or move without notice between any
 * two releases, including patch releases.
 */
package io.github.benhendayoussef.idempotency.internal.filter;
