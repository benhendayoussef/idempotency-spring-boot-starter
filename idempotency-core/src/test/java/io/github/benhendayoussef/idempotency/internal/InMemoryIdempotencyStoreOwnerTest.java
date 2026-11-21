package io.github.benhendayoussef.idempotency.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord.State;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore.ClaimResult;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Claim ownership at the store level (issue #19): a run may complete or release a key only while no
 * other run holds it. The behaviour tests prove the end-to-end sequences; these pin each rule.
 */
class InMemoryIdempotencyStoreOwnerTest {

    private static final Duration LONG = Duration.ofMinutes(5);

    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();

    private static IdempotencyRecord completed(String payload) {
        return new IdempotencyRecord(State.COMPLETED, "fp", 201, "java.lang.String", payload, Instant.now());
    }

    @Test
    void theOwnerCompletesItsOwnClaim() {
        store.claim("k", "fp", LONG, "a");

        assertThat(store.complete("k", "a", completed("\"a\""), LONG)).isTrue();
        assertThat(store.find("k")).get().extracting(IdempotencyRecord::payload).isEqualTo("\"a\"");
    }

    @Test
    void anotherOwnersLiveClaimBlocksCompletion() throws Exception {
        store.claim("k", "fp", Duration.ofMillis(50), "a");
        Thread.sleep(100);
        assertThat(store.claim("k", "fp", LONG, "b")).isInstanceOf(ClaimResult.Acquired.class);
        assertThat(store.complete("k", "b", completed("\"b\""), LONG)).isTrue();

        assertThat(store.complete("k", "a", completed("\"a\""), LONG))
                .as("a run whose claim was reclaimed must not overwrite the winner's response")
                .isFalse();
        assertThat(store.find("k")).get().extracting(IdempotencyRecord::payload).isEqualTo("\"b\"");
    }

    @Test
    void anotherOwnersLiveClaimBlocksRelease() throws Exception {
        store.claim("k", "fp", Duration.ofMillis(50), "a");
        Thread.sleep(100);
        store.claim("k", "fp", LONG, "b");

        assertThat(store.release("k", "a"))
                .as("a run whose claim was reclaimed must not delete the winner's claim")
                .isFalse();
        assertThat(store.claim("k", "fp", LONG, "c")).isInstanceOf(ClaimResult.AlreadyHeld.class);
    }

    @Test
    void theOwnerReleasesItsOwnClaim() {
        store.claim("k", "fp", LONG, "a");

        assertThat(store.release("k", "a")).isTrue();
        assertThat(store.claim("k", "fp", LONG, "b")).isInstanceOf(ClaimResult.Acquired.class);
    }

    /** What 1.1 did, and must keep doing: a slow run nobody competed with still stores its response. */
    @Test
    void anExpiredClaimNobodyReclaimedIsStillCompleted() throws Exception {
        store.claim("k", "fp", Duration.ofMillis(50), "a");
        Thread.sleep(100);

        assertThat(store.complete("k", "a", completed("\"a\""), LONG)).isTrue();
        assertThat(store.find("k")).get().extracting(IdempotencyRecord::payload).isEqualTo("\"a\"");
    }

    /** A claim made without an owner - by a caller or store version that predates them - is anyone's. */
    @Test
    void anUnownedClaimCanBeSettledByAnyOwner() {
        store.claim("k", "fp", LONG);

        assertThat(store.complete("k", "a", completed("\"a\""), LONG)).isTrue();

        store.claim("j", "fp", LONG);
        assertThat(store.release("j", "a")).isTrue();
        assertThat(store.find("j")).isEmpty();
    }

    @Test
    void releasingAKeyThatIsAlreadyGoneSucceeds() {
        assertThat(store.release("absent", "a")).isTrue();
    }
}
