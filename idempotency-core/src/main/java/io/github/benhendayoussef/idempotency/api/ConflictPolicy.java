package io.github.benhendayoussef.idempotency.api;

/** What to do when a second request arrives while the first is still {@code IN_PROGRESS}. */
public enum ConflictPolicy {
    DEFAULT,

    /** Poll until the in-flight request completes, then replay. */
    WAIT,

    /** Return 409 immediately with {@code Retry-After}. */
    FAIL_FAST
}
