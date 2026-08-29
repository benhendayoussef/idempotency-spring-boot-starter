package io.github.benhendayoussef.idempotency.webflux.internal;

import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Puts the {@link ServerWebExchange} into the Reactor context so the aspect can reach it.
 *
 * <p>The servlet aspect reads the current request from {@code RequestContextHolder}, a ThreadLocal.
 * That has no reactive equivalent: a single request is handled across whatever threads the operators
 * happen to run on, so there is no "current request" for a thread to hold. The Reactor context is
 * the reactive counterpart - it travels with the subscription rather than with a thread - and the
 * only way to get the exchange into it is a filter that writes it there.
 *
 * <p>The context propagates <em>upstream</em>, from subscriber toward publisher, which is why
 * {@code contextWrite} is applied to the result of {@code chain.filter(...)} rather than before it:
 * everything downstream of this filter, the handler and the aspect included, then sees the value.
 *
 * <p>Public only for auto-configuration in another module; <strong>not part of the supported
 * API</strong>.
 */
public class IdempotencyExchangeContextFilter implements WebFilter, Ordered {

    /** Context key. Class-name based so it cannot collide with an application's own entries. */
    public static final String CONTEXT_KEY = IdempotencyExchangeContextFilter.class.getName();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange).contextWrite(ctx -> ctx.put(CONTEXT_KEY, exchange));
    }

    /**
     * Runs before anything that might need the exchange. Ordered explicitly rather than left to
     * registration order, because a filter that writes the context after the handler has already
     * been invoked would be silently useless - the aspect would just never find the exchange.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
