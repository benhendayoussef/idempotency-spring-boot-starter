package io.github.benhendayoussef.idempotency.autoconfigure;

import io.github.benhendayoussef.idempotency.api.NoScopeResolverConfiguredException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns a buried {@link NoScopeResolverConfiguredException} into an actionable startup message. */
public class MissingScopeResolverFailureAnalyzer extends AbstractFailureAnalyzer<NoScopeResolverConfiguredException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, NoScopeResolverConfiguredException cause) {
        return new FailureAnalysis(
                cause.getMessage() + ".",
                "If you meant USER scope, add spring-boot-starter-security (or spring-security-core) so "
                        + "the built-in principal-based resolver can register, and ensure requests are "
                        + "authenticated. If you meant TENANT scope, add spring-security-oauth2-jose. "
                        + "Otherwise set idempotency.scope=global, or register your own ScopeResolver bean.",
                cause);
    }
}
