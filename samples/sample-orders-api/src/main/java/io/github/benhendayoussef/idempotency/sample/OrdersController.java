package io.github.benhendayoussef.idempotency.sample;

import io.github.benhendayoussef.idempotency.api.Idempotent;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A fake payment endpoint. {@code chargeCount} stands in for "actually charged the customer's
 * card" - the point of the demo is that it only increments once no matter how many times the
 * same {@code Idempotency-Key} is retried.
 */
@RestController
@RequestMapping("/orders")
public class OrdersController {

    private final AtomicInteger chargeCount = new AtomicInteger();

    @Idempotent
    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(@RequestBody OrderRequest request) {
        int charge = chargeCount.incrementAndGet();
        String orderId = UUID.randomUUID().toString();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new OrderResponse(orderId, request.amount(), request.currency(), charge));
    }

    @GetMapping("/charge-count")
    public int chargeCount() {
        return chargeCount.get();
    }

    public record OrderRequest(BigDecimal amount, String currency) {
    }

    public record OrderResponse(String orderId, BigDecimal amount, String currency, int chargeNumber) {
    }
}
