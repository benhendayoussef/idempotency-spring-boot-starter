package io.github.benhendayoussef.idempotency.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.benhendayoussef.idempotency.api.Idempotent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class IdempotentMethodVisibilityValidatorTest {

    private final IdempotentMethodVisibilityValidator validator = new IdempotentMethodVisibilityValidator();
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(IdempotentMethodVisibilityValidator.class)).addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(IdempotentMethodVisibilityValidator.class)).detachAppender(appender);
    }

    static class HasPublicIdempotentMethod {
        @Idempotent
        public void handle() {
        }
    }

    static class HasPackagePrivateIdempotentMethod {
        @Idempotent
        void handle() {
        }
    }

    static class HasProtectedIdempotentMethod {
        @Idempotent
        protected void handle() {
        }
    }

    @Test
    void warnsWhenIdempotentIsOnAPackagePrivateMethod() {
        validator.postProcessBeforeInitialization(new HasPackagePrivateIdempotentMethod(), "bean");

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("HasPackagePrivateIdempotentMethod")
                .contains("handle")
                .contains("self-invocation");
    }

    @Test
    void warnsWhenIdempotentIsOnAProtectedMethod() {
        validator.postProcessBeforeInitialization(new HasProtectedIdempotentMethod(), "bean");

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void doesNotWarnWhenIdempotentIsOnAPublicMethod() {
        validator.postProcessBeforeInitialization(new HasPublicIdempotentMethod(), "bean");

        assertThat(appender.list).isEmpty();
    }

    @Test
    void doesNotWarnForBeansWithNoIdempotentMethodsAtAll() {
        validator.postProcessBeforeInitialization(new Object(), "bean");

        assertThat(appender.list).isEmpty();
    }
}
