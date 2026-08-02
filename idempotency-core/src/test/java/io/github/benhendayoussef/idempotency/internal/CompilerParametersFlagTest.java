package io.github.benhendayoussef.idempotency.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code -parameters} is what lets {@code @Idempotent(key = "#someArgName")} resolve real
 * argument names via reflection instead of failing to bind or silently reading {@code arg0}.
 * {@link Parameter#isNamePresent()} reports {@code true} only when the compiler emitted the flag
 * - unlike local-variable-table-based name discovery, it has no bytecode-debug-info fallback, so
 * this is the direct signal that would go false (and this test red) if the flag were ever dropped
 * from a module's {@code JavaCompile} configuration.
 */
class CompilerParametersFlagTest {

    interface Target {
        void namedArgs(Map<String, Integer> orderBody, String idempotencyKey);
    }

    @Test
    void methodParametersCarryTheirDeclaredNamesAtRuntime() throws NoSuchMethodException {
        Method method = Target.class.getMethod("namedArgs", Map.class, String.class);

        assertThat(method.getParameters()[0].isNamePresent()).isTrue();
        assertThat(method.getParameters()[0].getName()).isEqualTo("orderBody");
        assertThat(method.getParameters()[1].isNamePresent()).isTrue();
        assertThat(method.getParameters()[1].getName()).isEqualTo("idempotencyKey");
    }
}
