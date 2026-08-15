# Draft closing comment for issue #1

**Not posted.** Paste this into the issue when the branch merges.

---

Shipped on `feature/jdbc-tx-join` as **`idempotency.jdbc.join-transaction`** (boolean, default
`false`).

With it on, the aspect wraps `pjp.proceed()` and `store.complete()` in one programmatic transaction,
so a handler's own `@Transactional(REQUIRED)` joins it instead of opening and closing its own first.
One physical commit covers the business data and the completion record — verified by counting real
begins/commits on a decorated `PlatformTransactionManager`, not inferred. The crash window this
issue describes no longer exists in that mode.

The claim still commits independently, outside the wrapping transaction. It has to: if the
`IN_PROGRESS` row only became visible when the handler committed, every concurrent duplicate would
race past it. A rollback therefore leaves an orphaned claim, and the aspect releases it — including
on the `UnexpectedRollbackException` that a silent `setRollbackOnly()` produces at commit time.

**Default off.** Joining changes execution for every `@Idempotent` method, not just transactional
ones: a handler with no `@Transactional` of its own gets pulled into a transaction, and a handler's
own `@Transactional(timeout)` stops applying once it joins. Too large a change to impose on an
upgrade, so it is opt-in and the README states both modes.

**§2.2 decision — 4xx: option (b), write the terminal state after the rollback in its own
transaction.** The 4xx-keeps policy is unchanged in both modes. The reasoning: in the default mode
the 4xx record survives precisely *because* `complete()` runs in an independent transaction, so
option (b) reproduces the existing shape rather than inventing a new one. Option (a) — releasing on
4xx in joined mode — would have made a property named `join-transaction` silently redefine the
failure policy, and would have left the two modes disagreeing about what a retry after a 4xx does.
The cost of (b) is documented rather than eliminated: the replayed 4xx body may describe business
rows that rolled back, so don't build a 4xx response out of rows written in the same request. That
hazard is identical in the default mode.

**One defect found in the new path and fixed before merge:** oversized responses (over
`idempotency.max-payload-size`) are released rather than cached, and `release()` is `REQUIRES_NEW` —
which in the first version ran inside the shared transaction, checking out a second connection while
the first was held. Deadlock shape at pool saturation. The release now happens after the transaction
closes, and a test asserts no nested transaction is ever opened on the normal joined path.

**Known gap, documented not fixed:** a handler annotated `@Transactional(REQUIRES_NEW)` opts out of
joining, so atomicity does not hold for it — its transaction and the completion record no longer
share a fate. `REQUIRES_NEW` is an explicit instruction not to participate and overriding it would
be a worse surprise than the gap. Asserted as a test so the behaviour is pinned rather than assumed.

**Out of scope for this phase:** WebFlux, MySQL, and any Redis change. Redis has no transaction to
join and stays at-least-once by design; setting the property while Redis is active logs a WARN and
no-ops rather than failing startup, so a shared profile that flips it can't take a service down.

Full write-up, including the test matrix and what was and was not run: `V02-PHASE-A-REPORT.md`.
