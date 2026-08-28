package io.github.benhendayoussef.idempotency.internal.filter;

import java.util.List;
import java.util.Map;

/**
 * A complete HTTP response, captured verbatim so it can be written again byte for byte.
 *
 * <p>This is what {@code idempotency.mode=filter} stores instead of a handler return value. The
 * difference matters for anything the aspect mode cannot see: a response body written straight to
 * the {@code HttpServletResponse}, a header set by a filter further down the chain, a content type
 * negotiated at write time. Under aspect mode a replay re-serializes the return value and hopes the
 * result matches; here there is nothing to re-derive.
 *
 * <p>The body is carried as Base64 rather than a string because it is bytes, not text - a binary
 * response, or any charset other than the one that happened to be in use when it was read back,
 * would otherwise be silently corrupted on replay.
 *
 * @param status  the status code as originally sent
 * @param headers response headers worth replaying, filtered by the configured allowlist - see
 *                {@code IdempotencyProperties.Filter#getReplayHeaders()}
 * @param body    the raw response body, Base64-encoded
 */
public record CapturedResponse(int status, Map<String, List<String>> headers, String body) {
}
