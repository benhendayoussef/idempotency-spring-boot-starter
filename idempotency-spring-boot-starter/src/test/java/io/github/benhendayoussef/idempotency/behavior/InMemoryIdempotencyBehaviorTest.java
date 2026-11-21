package io.github.benhendayoussef.idempotency.behavior;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * The full behavioural matrix against the built-in in-memory store.
 *
 * <p>It is the {@code idempotency.store=memory} fallback, so it carries the same claim/complete/
 * release contract as the stores that ship in their own modules and is held to the same matrix.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = AbstractIdempotencyBehaviorTest.TestApp.class,
        properties = {"idempotency.store=memory",
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE_AND_SECURITY})
class InMemoryIdempotencyBehaviorTest extends AbstractIdempotencyBehaviorTest {
}
