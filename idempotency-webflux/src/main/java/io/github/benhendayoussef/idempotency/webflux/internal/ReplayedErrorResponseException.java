package io.github.benhendayoussef.idempotency.webflux.internal;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ResponseStatusException;

/**
 * Carries a replayed error response back out through the reactive pipeline.
 *
 * <p>The servlet aspect can write a stored error straight to the {@code HttpServletResponse} when the
 * handler's declared return type cannot express an arbitrary status. There is no equivalent here -
 * writing to the response mid-pipeline races the framework's own rendering - so the stored status and
 * body are surfaced as an exception instead.
 *
 * <p>It extends {@link ResponseStatusException} specifically, rather than merely implementing
 * {@code ErrorResponse}. WebFlux's status handling maps {@code ResponseStatusException}; a plain
 * exception that only implements {@code ErrorResponse} falls through to the generic handler and
 * renders <strong>500</strong>. That turns a replayed 400 into a server error - the one answer a
 * retry must never get, and worse than not replaying at all, since the caller now sees a failure
 * that never actually happened.
 */
final class ReplayedErrorResponseException extends ResponseStatusException {

    private static final long serialVersionUID = 1L;

    ReplayedErrorResponseException(int status, ProblemDetail stored) {
        super(HttpStatusCode.valueOf(status), stored != null ? stored.getDetail() : null);
        copyInto(getBody(), stored);
    }

    /**
     * {@code getBody()} is final on the superclass, so the recorded body is copied onto the one this
     * exception already owns rather than replacing it. The effect is the same and it is what matters
     * here: a retry sees the title, detail and extension members the first caller saw, not a fresh
     * ProblemDetail rebuilt from the status alone.
     */
    private static void copyInto(ProblemDetail target, ProblemDetail stored) {
        if (stored == null) {
            return;
        }
        if (stored.getTitle() != null) {
            target.setTitle(stored.getTitle());
        }
        if (stored.getType() != null) {
            target.setType(stored.getType());
        }
        if (stored.getInstance() != null) {
            target.setInstance(stored.getInstance());
        }
        if (stored.getProperties() != null) {
            stored.getProperties().forEach(target::setProperty);
        }
    }
}
