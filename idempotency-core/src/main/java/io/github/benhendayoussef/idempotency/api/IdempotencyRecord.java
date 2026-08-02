package io.github.benhendayoussef.idempotency.api;

import java.time.Instant;

/**
 * The persisted state of one idempotency key.
 *
 * <p>{@code payloadType} is an integrity check only, never a deserialization hint: the caller
 * must resolve the target type from the live method signature, not from this field. Trusting a
 * type name coming out of a store is a classic polymorphic-deserialization gadget vector.
 */
public record IdempotencyRecord(
        State state,
        String fingerprint,
        Integer status,          // null while IN_PROGRESS
        String payloadType,      // canonical type name, integrity check only
        String payload,          // JSON
        Instant createdAt
) {
    public enum State { IN_PROGRESS, COMPLETED }

    public static IdempotencyRecord inProgress(String fingerprint, Instant now) {
        return new IdempotencyRecord(State.IN_PROGRESS, fingerprint, null, null, null, now);
    }
}
