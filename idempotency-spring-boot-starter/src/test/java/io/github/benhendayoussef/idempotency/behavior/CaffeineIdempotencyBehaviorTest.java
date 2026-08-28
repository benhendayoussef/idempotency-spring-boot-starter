package io.github.benhendayoussef.idempotency.behavior;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * The full behavioural matrix against the Caffeine store.
 *
 * <p>No container needed - the point of this store is that it runs in-process - but the matrix is
 * still worth running in full rather than trusting the unit tests. The store's contract is defined
 * by what the aspect does with it, and the semantics that matter most here (a claim being visible
 * to a concurrent duplicate, expiry being observed on the boundary) are exercised far harder by the
 * concurrency soak and the TTL cases than by direct calls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = AbstractIdempotencyBehaviorTest.TestApp.class,
        properties = {"idempotency.store=caffeine",
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE_AND_SECURITY})
class CaffeineIdempotencyBehaviorTest extends AbstractIdempotencyBehaviorTest {
}
