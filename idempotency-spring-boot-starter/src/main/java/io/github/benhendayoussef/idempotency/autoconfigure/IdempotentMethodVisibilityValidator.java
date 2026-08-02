package io.github.benhendayoussef.idempotency.autoconfigure;

import io.github.benhendayoussef.idempotency.api.Idempotent;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Warns at startup when {@code @Idempotent} is found on a non-public method: the AOP proxy only
 * intercepts calls that arrive from outside the bean, so a public caller works but self-invocation
 * (one method on the bean calling another directly) silently bypasses it - the same well-known
 * limitation as {@code @Transactional}. Runs as a {@link BeanPostProcessor} so it inspects the real
 * target class, before any AOP proxy wraps it.
 */
public class IdempotentMethodVisibilityValidator implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(IdempotentMethodVisibilityValidator.class);

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        for (Method method : bean.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(Idempotent.class) && !Modifier.isPublic(method.getModifiers())) {
                log.warn("@Idempotent on non-public method {}#{} will not be intercepted by the proxy on "
                                + "self-invocation (a call from another method on the same bean) - "
                                + "make it public, or call it through another bean.",
                        bean.getClass().getName(), method.getName());
            }
        }
        return bean;
    }
}
