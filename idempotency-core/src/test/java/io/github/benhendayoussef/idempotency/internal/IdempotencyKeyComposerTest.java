package io.github.benhendayoussef.idempotency.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.HandlerMapping;

@ExtendWith(MockitoExtension.class)
class IdempotencyKeyComposerTest {

    private final IdempotencyKeyComposer composer = new IdempotencyKeyComposer();

    @Mock
    private HttpServletRequest request;

    @Test
    void differentRoutesForTheSameClientKeyDoNotCollide() {
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/orders", "/refunds");
        when(request.getMethod()).thenReturn("POST");

        String orders = composer.compose("same-key", "", request);
        String refunds = composer.compose("same-key", "", request);

        assertThat(orders).isNotEqualTo(refunds);
    }

    @Test
    void differentNamespacesForTheSameClientKeyDoNotCollide() {
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn("/orders");
        when(request.getMethod()).thenReturn("POST");

        String userA = composer.compose("abc-123", "user-a", request);
        String userB = composer.compose("abc-123", "user-b", request);

        assertThat(userA).isNotEqualTo(userB);
    }

    @Test
    void sameInputsProduceTheSameKey() {
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn("/orders");
        when(request.getMethod()).thenReturn("POST");

        String first = composer.compose("abc-123", "user-a", request);
        String second = composer.compose("abc-123", "user-a", request);

        assertThat(first).isEqualTo(second);
    }
}
