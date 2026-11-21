package io.github.benhendayoussef.idempotency.internal;

import io.github.benhendayoussef.idempotency.api.IdempotencyMetrics;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What every caller of the owner-aware store methods does when one returns {@code false}: the
 * request's claim expired while its handler ran and another request has claimed the key since
 * (issue #19). The request still answers its own client - its handler ran - but leaves the key to
 * the request that holds it now.
 */
public final class LostClaim {

    private static final Logger log = LoggerFactory.getLogger(LostClaim.class);

    private LostClaim() {
    }

    /**
     * @param notDone what was withheld from the key, as a past participle: "stored" or "released"
     */
    public static void report(IdempotencyMetrics metrics, Duration claimTtl, String storeKey, String notDone) {
        metrics.lostClaim();
        log.warn("The claim on idempotency key {} expired while its handler ran, and another request has "
                + "claimed the key since, so the key was not {} by the request that lost it. The handler "
                + "outlived idempotency.claim-ttl ({}); raise it above your slowest handler",
                storeKey, notDone, claimTtl);
    }
}
