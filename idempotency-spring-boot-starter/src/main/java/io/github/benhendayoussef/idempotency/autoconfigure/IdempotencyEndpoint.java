package io.github.benhendayoussef.idempotency.autoconfigure;

import io.github.benhendayoussef.idempotency.api.IdempotencyKeys;
import io.github.benhendayoussef.idempotency.api.IdempotencyRecord;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.lang.Nullable;

/**
 * Management endpoint for inspecting and evicting a single idempotency key.
 *
 * <p>Exists because of a specific operational dead end. When a process dies mid-request its key is
 * left {@code IN_PROGRESS}, and until the claim lease expires every retry gets a 409. Before 0.4
 * there was no supported way to look at that record or clear it - the storage key is a digest, and
 * the code that computed it was internal. The remaining options were direct surgery on Redis or the
 * database.
 *
 * <p>Addressing a record needs the same three things that went into its key: the client's key, the
 * HTTP method, and the <strong>route pattern</strong> ({@code /orders/{id}}, not {@code /orders/42}).
 * Add {@code namespace} for a {@code USER}- or {@code TENANT}-scoped endpoint.
 *
 * <pre>
 * GET    /actuator/idempotency?key=abc123&amp;method=POST&amp;route=/orders
 * DELETE /actuator/idempotency?key=abc123&amp;method=POST&amp;route=/orders
 * </pre>
 *
 * <p><strong>The stored response body is deliberately not returned.</strong> It is the application's
 * own response - frequently customer data - and an actuator endpoint is the wrong place to expose
 * it. The size is reported instead, which is what diagnosis actually needs.
 *
 * <p><strong>This endpoint is sensitive.</strong> Evicting a key lets the next duplicate execute for
 * real, so anyone who can reach it can defeat idempotency for a request they can name. Actuator
 * exposes only {@code health} and {@code info} over HTTP by default, so opting in is explicit -
 * secure it accordingly.
 */
@Endpoint(id = "idempotency")
public class IdempotencyEndpoint {

    private final IdempotencyStore store;

    public IdempotencyEndpoint(IdempotencyStore store) {
        this.store = store;
    }

    /** Looks up a record without modifying it. */
    @ReadOperation
    public Map<String, Object> inspect(String key, String method, String route,
            @Nullable String namespace) {
        String storageKey = IdempotencyKeys.storageKey(key, method, route, namespace);
        Optional<IdempotencyRecord> found = store.find(storageKey);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("storageKey", storageKey);
        result.put("found", found.isPresent());
        found.ifPresent(record -> {
            result.put("state", record.state().name());
            result.put("status", record.status());
            result.put("fingerprint", record.fingerprint());
            result.put("createdAt", record.createdAt().toString());
            result.put("payloadType", record.payloadType());
            // Size, never the body itself - see the class javadoc.
            result.put("payloadBytes", record.payload() == null ? 0 : record.payload().length());
        });
        return result;
    }

    /**
     * Drops the record so the next request with this key executes for real.
     *
     * <p>Reports whether anything was actually there. "Not found" is the common and harmless case -
     * the key may already have expired, or the caller may have the route pattern slightly wrong,
     * and those two look identical from here. Saying so plainly beats reporting a success that
     * evicted nothing.
     */
    @DeleteOperation
    public Map<String, Object> evict(String key, String method, String route,
            @Nullable String namespace) {
        String storageKey = IdempotencyKeys.storageKey(key, method, route, namespace);
        boolean existed = store.find(storageKey).isPresent();
        store.release(storageKey);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("storageKey", storageKey);
        result.put("evicted", existed);
        if (!existed) {
            result.put("note", "No record was present. It may have expired already, or the route "
                    + "pattern/namespace may not match the one the key was stored under.");
        }
        return result;
    }
}
