package io.github.benhendayoussef.idempotency.internal;

import io.github.benhendayoussef.idempotency.api.IdempotencyMetrics;

/** Default {@link IdempotencyMetrics} used when no user-supplied bean overrides it. */
public class NoOpIdempotencyMetrics implements IdempotencyMetrics {
}
