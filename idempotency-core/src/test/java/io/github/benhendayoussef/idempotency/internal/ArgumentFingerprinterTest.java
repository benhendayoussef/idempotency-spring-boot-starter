package io.github.benhendayoussef.idempotency.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArgumentFingerprinterTest {

    private final ArgumentFingerprinter fingerprinter = new ArgumentFingerprinter();

    interface Target {
        void mapArg(Map<String, Integer> body);

        void withRequest(HttpServletRequest request, Map<String, Integer> body);
    }

    @Test
    void mapKeyOrderDoesNotChangeTheHash() throws NoSuchMethodException {
        Method method = Target.class.getMethod("mapArg", Map.class);

        Map<String, Integer> ab = new LinkedHashMap<>();
        ab.put("a", 1);
        ab.put("b", 2);

        Map<String, Integer> ba = new LinkedHashMap<>();
        ba.put("b", 2);
        ba.put("a", 1);

        String hashAb = fingerprinter.fingerprint(method, new Object[] { ab });
        String hashBa = fingerprinter.fingerprint(method, new Object[] { ba });

        assertThat(hashAb).isNotBlank().isEqualTo(hashBa);
    }

    @Test
    void differentBodyProducesADifferentHash() throws NoSuchMethodException {
        Method method = Target.class.getMethod("mapArg", Map.class);

        String hash1 = fingerprinter.fingerprint(method, new Object[] { Map.of("a", 1) });
        String hash2 = fingerprinter.fingerprint(method, new Object[] { Map.of("a", 2) });

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void addingAnHttpServletRequestParameterDoesNotChangeTheHash() throws NoSuchMethodException {
        Method withoutRequest = Target.class.getMethod("mapArg", Map.class);
        Method withRequest = Target.class.getMethod("withRequest", HttpServletRequest.class, Map.class);

        Map<String, Integer> body = Map.of("a", 1);

        String hashWithout = fingerprinter.fingerprint(withoutRequest, new Object[] { body });
        String hashWith = fingerprinter.fingerprint(withRequest, new Object[] { null, body });

        assertThat(hashWithout).isEqualTo(hashWith);
    }
}
