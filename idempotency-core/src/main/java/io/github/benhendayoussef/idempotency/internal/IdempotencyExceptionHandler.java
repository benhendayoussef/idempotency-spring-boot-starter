package io.github.benhendayoussef.idempotency.internal;

import io.github.benhendayoussef.idempotency.api.FingerprintMismatchException;
import io.github.benhendayoussef.idempotency.api.IdempotencyConflictException;
import io.github.benhendayoussef.idempotency.api.IdempotencyKeyRequiredException;
import io.github.benhendayoussef.idempotency.api.IdempotencyPrincipalRequiredException;
import io.github.benhendayoussef.idempotency.api.IdempotencyStoreUnavailableException;
import io.github.benhendayoussef.idempotency.config.IdempotencyProperties;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 {@link ProblemDetail} responses for the library's own exceptions. */
@RestControllerAdvice
public class IdempotencyExceptionHandler {

    private static final String DOC_BASE = "https://github.com/benhendayoussef/idempotency-spring-boot-starter/blob/main/README.md";

    private static final long MIN_RETRY_AFTER_SECONDS = 1;
    private static final long MAX_RETRY_AFTER_SECONDS = 5;

    private final IdempotencyProperties props;

    public IdempotencyExceptionHandler(IdempotencyProperties props) {
        this.props = props;
    }

    /**
     * Retry-After hint on a conflict: 10% of {@code idempotency.wait-timeout}, clamped to
     * [1s, 5s]. A fixed 1s regardless of a much longer configured wait-timeout means every client
     * that honours the header retries at the same cadence - a thundering-herd generator under load.
     * Scaling with the configured timeout (while still capping it) keeps the hint proportional to
     * how long the in-flight request is actually expected to take, without letting a very long
     * wait-timeout produce an equally long, unhelpful Retry-After. Computed per-call, not cached at
     * construction time - {@code IdempotencyProperties} is a mutable singleton bean the rest of the
     * codebase already treats as live (e.g. {@code IdempotencyAspect} reads it fresh on every
     * request), so this must too.
     */
    private String retryAfterSeconds() {
        long tenPercent = props.getWaitTimeout().toSeconds() / 10;
        long clamped = Math.max(MIN_RETRY_AFTER_SECONDS, Math.min(MAX_RETRY_AFTER_SECONDS, tenPercent));
        return String.valueOf(clamped);
    }

    @ExceptionHandler(IdempotencyKeyRequiredException.class)
    ProblemDetail keyRequired(IdempotencyKeyRequiredException e) {
        var pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Idempotency key required");
        pd.setDetail(e.getMessage());
        pd.setType(URI.create(DOC_BASE + "#idempotency-key-required"));
        return pd;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> conflict(IdempotencyConflictException e) {
        var pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Request already in progress");
        pd.setDetail(e.getMessage());
        pd.setType(URI.create(DOC_BASE + "#conflict"));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", retryAfterSeconds())
                .body(pd);
    }

    @ExceptionHandler(FingerprintMismatchException.class)
    ProblemDetail fingerprintMismatch(FingerprintMismatchException e) {
        var pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setTitle("Idempotency key reused with a different request body");
        pd.setDetail(e.getMessage());
        pd.setType(URI.create(DOC_BASE + "#fingerprint-mismatch"));
        return pd;
    }

    @ExceptionHandler(IdempotencyPrincipalRequiredException.class)
    ProblemDetail principalRequired(IdempotencyPrincipalRequiredException e) {
        var pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Authenticated principal required");
        pd.setDetail(e.getMessage());
        pd.setType(URI.create(DOC_BASE + "#principal-required"));
        return pd;
    }

    @ExceptionHandler(IdempotencyStoreUnavailableException.class)
    ProblemDetail storeUnavailable(IdempotencyStoreUnavailableException e) {
        var pd = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        pd.setTitle("Idempotency store unavailable");
        pd.setType(URI.create(DOC_BASE + "#store-unavailable"));
        return pd;
    }
}
