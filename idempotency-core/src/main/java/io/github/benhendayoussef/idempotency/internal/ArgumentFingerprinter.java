package io.github.benhendayoussef.idempotency.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.benhendayoussef.idempotency.api.IdempotencyIgnore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Set;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * Fingerprints the fingerprint-relevant method arguments so a reused key with a different
 * payload is rejected instead of silently replayed. Uses its own {@link JsonMapper} instance,
 * configured for deterministic key ordering — never the application's {@code ObjectMapper},
 * since users mutate theirs.
 */
public class ArgumentFingerprinter {

    // Principal.class also matches Spring Security's Authentication (it extends Principal),
    // without forcing a hard runtime dependency on spring-security-core.
    private static final Set<Class<?>> SKIPPED = Set.of(
            HttpServletRequest.class, HttpServletResponse.class,
            Principal.class, BindingResult.class, MultipartFile.class);

    private final JsonMapper mapper;

    public ArgumentFingerprinter() {
        this.mapper = JsonMapper.builder()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .addModule(new JavaTimeModule())
                .build();
    }

    public String fingerprint(Method method, Object[] args) {
        Parameter[] params = method.getParameters();
        var payload = new ArrayList<Object>();
        for (int i = 0; i < args.length; i++) {
            if (isSkipped(params[i], args[i])) {
                continue;
            }
            payload.add(args[i]);
        }
        try {
            return Hashing.sha256Hex(mapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            // Non-serializable arg: degrade to no fingerprinting rather than failing the request.
            // Never let a hashing failure turn into a 500 for something the library couldn't hash.
            return "";
        }
    }

    private boolean isSkipped(Parameter param, Object arg) {
        if (param.isAnnotationPresent(IdempotencyIgnore.class)) {
            return true;
        }
        for (Class<?> skipped : SKIPPED) {
            if (skipped.isAssignableFrom(param.getType())) {
                return true;
            }
        }
        return arg != null && SKIPPED.stream().anyMatch(c -> c.isInstance(arg));
    }
}
