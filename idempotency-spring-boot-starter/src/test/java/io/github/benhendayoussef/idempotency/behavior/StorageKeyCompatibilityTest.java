package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.benhendayoussef.idempotency.api.IdempotencyKeys;
import io.github.benhendayoussef.idempotency.internal.IdempotencyKeyComposer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Every path must derive the same storage key for the same request.
 *
 * <p>Both the WebFlux and filter-mode changes claimed keys were byte-identical across stacks - that
 * a service can migrate MVC to WebFlux, or switch {@code idempotency.mode}, without orphaning
 * everything already in the store. Neither actually asserted it, and there were three separate
 * copies of the hash format for it to drift between. There is now one definition
 * ({@link IdempotencyKeys}) and this pins the claim.
 */
class StorageKeyCompatibilityTest {

    private final IdempotencyKeyComposer servletComposer = new IdempotencyKeyComposer();

    @Test
    void theServletComposerAgreesWithThePublicKeyApi() {
        var request = new MockHttpServletRequest("POST", "/orders/42");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");

        assertThat(servletComposer.compose("client-key-1", "", request))
                .as("a key computed through the public API must address the same record the aspect wrote")
                .isEqualTo(IdempotencyKeys.storageKey("client-key-1", "POST", "/orders/{id}"));
    }

    @Test
    void theNamespaceIsPartOfTheKeySoScopesCannotCollide() {
        // The whole point of USER/TENANT scoping: the same client key from two callers must not
        // resolve to one record.
        String global = IdempotencyKeys.storageKey("same-key", "POST", "/orders");
        String alice = IdempotencyKeys.storageKey("same-key", "POST", "/orders", "alice");
        String bob = IdempotencyKeys.storageKey("same-key", "POST", "/orders", "bob");

        assertThat(global).isNotEqualTo(alice);
        assertThat(alice).isNotEqualTo(bob);
    }

    @Test
    void theRoutePatternRatherThanTheUriIsWhatDistinguishesEndpoints() {
        // /orders/1 and /orders/2 are the same endpoint. If the URI were hashed instead, a client
        // reusing one key across them would not be protected.
        var first = new MockHttpServletRequest("POST", "/orders/1");
        first.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");
        var second = new MockHttpServletRequest("POST", "/orders/2");
        second.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");

        assertThat(servletComposer.compose("k", "", first))
                .isEqualTo(servletComposer.compose("k", "", second));
    }

    @Test
    void differentMethodsOnOneRouteDoNotShareAKey() {
        assertThat(IdempotencyKeys.storageKey("k", "POST", "/orders"))
                .isNotEqualTo(IdempotencyKeys.storageKey("k", "PUT", "/orders"));
    }

    @Test
    void theKeyIsAFixedLengthDigestSoAnOversizedHeaderCannotBloatTheStore() {
        String huge = "x".repeat(8_000);
        assertThat(IdempotencyKeys.storageKey(huge, "POST", "/orders"))
                .hasSize(64)
                .isEqualTo(IdempotencyKeys.storageKey(huge, "POST", "/orders"));
    }

    @Test
    void missingInputsAreRejectedRatherThanHashedIntoAValidLookingKey() {
        // Silently hashing a null would produce a real key that addresses nothing - the worst
        // outcome for someone trying to evict a stuck record.
        assertThatThrownBy(() -> IdempotencyKeys.storageKey(null, "POST", "/orders"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("clientKey");
        assertThatThrownBy(() -> IdempotencyKeys.storageKey("k", "  ", "/orders"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("httpMethod");
        assertThatThrownBy(() -> IdempotencyKeys.storageKey("k", "POST", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("routePattern");
    }

    @Test
    void aNullNamespaceIsTreatedAsGlobalRatherThanRejected() {
        // GLOBAL scope passes an empty namespace internally; null arriving from a caller means the
        // same thing and should not blow up.
        assertThat(IdempotencyKeys.storageKey("k", "POST", "/orders", null))
                .isEqualTo(IdempotencyKeys.storageKey("k", "POST", "/orders", ""));
    }
}
