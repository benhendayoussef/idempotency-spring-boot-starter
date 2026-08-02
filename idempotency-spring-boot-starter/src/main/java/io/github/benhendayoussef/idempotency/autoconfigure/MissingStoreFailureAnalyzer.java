package io.github.benhendayoussef.idempotency.autoconfigure;

import io.github.benhendayoussef.idempotency.api.NoStoreConfiguredException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns a buried {@link NoStoreConfiguredException} into an actionable startup message. */
public class MissingStoreFailureAnalyzer extends AbstractFailureAnalyzer<NoStoreConfiguredException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, NoStoreConfiguredException cause) {
        return new FailureAnalysis(
                "No IdempotencyStore is configured.",
                "Add spring-boot-starter-data-redis (idempotency.store defaults to redis when it's on the "
                        + "classpath), or set idempotency.store=jdbc with a DataSource on the classpath, or "
                        + "set idempotency.store=memory for local development/tests.",
                cause);
    }
}
