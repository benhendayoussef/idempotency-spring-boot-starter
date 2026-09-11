package io.github.benhendayoussef.idempotency.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@code claimTtlFor} is shared by all three execution paths - the servlet aspect, the reactive
 * aspect and the filter - so it is worth pinning on its own rather than only through one of them.
 */
class ClaimTtlTest {

    private final IdempotencyProperties props = new IdempotencyProperties();

    @Test
    void theLeaseDefaultsToFiveMinutesNotTheRetentionWindow() {
        // The whole point of 0.4: a crashed process holds a key for minutes, not a day.
        assertThat(props.getClaimTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.getDefaultTtl()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void theLeaseIsUsedWhenItIsShorterThanTheRetentionWindow() {
        assertThat(props.claimTtlFor(Duration.ofHours(24))).isEqualTo(Duration.ofMinutes(5));
    }

    /**
     * The cap, and the reason this is safe to enable by default: it can never produce a claim held
     * longer than 0.3 held it, because 0.3 always used the retention TTL.
     */
    @Test
    void theRetentionWindowCapsTheLeaseWhenItIsShorter() {
        assertThat(props.claimTtlFor(Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void anEqualLeaseAndRetentionWindowResolveToThatValue() {
        props.setClaimTtl(Duration.ofMinutes(10));
        assertThat(props.claimTtlFor(Duration.ofMinutes(10))).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void raisingTheLeaseAboveTheDefaultIsHonouredForLongRunningHandlers() {
        // The documented escape hatch for a handler that legitimately runs longer than five minutes.
        props.setClaimTtl(Duration.ofMinutes(30));
        assertThat(props.claimTtlFor(Duration.ofHours(24))).isEqualTo(Duration.ofMinutes(30));
    }
}
