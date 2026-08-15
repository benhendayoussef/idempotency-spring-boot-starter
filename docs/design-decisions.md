# Design decisions

Why this library is built the way it is. For how each class works, see
[`explained/`](explained/00-overview.md); for how to use it, see the
[README](../README.md).

## Interception strategy: filter vs interceptor vs AOP

Three viable approaches to intercepting a request for replay:

| | How | Fidelity | Complexity |
|---|---|---|---|
| Servlet `Filter` | Wrap request/response, cache bytes, resolve the handler manually | Byte-exact replay | High |
| `HandlerInterceptor` | `preHandle`/`postHandle`, still needs a filter for body caching | Medium | Medium |
| **AOP `@Around`** | Fingerprint the *method arguments*, store the *return value* | Type-level, not byte-level | Low |

**This library uses AOP.** The reasoning:

1. **The fingerprint comes for free.** By the time the handler method runs, the request body has
   already been parsed into method arguments — no stream-consumption problem to solve.
   `ContentCachingRequestWrapper` only caches bytes *after* something reads them, so the filter
   approach needs a custom buffering request wrapper up front. That is a whole subsystem.
2. **Re-serializing the return object is more correct than replaying bytes.** If the client's
   `Accept` header differs on the retry, byte replay would send the wrong content type. Object
   replay goes back through normal `HttpMessageConverter` negotiation.

**The tradeoff:** anything the handler writes directly to `HttpServletResponse` is not captured,
and streaming/SSE returns are not supported. In `@RestController` code that returns a value, this
is rarely a constraint — but it is a real one, and it is listed in the README's limitations.

A `mode: filter` option for byte-exact replay is on the roadmap for 0.2. The store SPI is
deliberately designed so that implementation can slot in behind the same interface.

**Self-invocation is bypassed**, the same well-known limitation as `@Transactional`: an internal
`this.method()` call never goes through the proxy. A startup check logs a warning when
`@Idempotent` is found on a non-public method, which is the most common way to hit this by accident.

## The state machine

```
                       ┌──────────────┐
   request ───claim───►│ IN_PROGRESS  │
                       └──────┬───────┘
                              │
              ┌───────────────┼─────────────────┐
              │               │                 │
        handler returns   handler throws    handler throws
         2xx or 4xx          5xx-ish        4xx-mapped exc
              │               │                 │
              ▼               ▼                 ▼
        ┌───────────┐    ┌─────────┐      ┌───────────┐
        │ COMPLETED │    │ RELEASED│      │ COMPLETED │
        │  (replay) │    │ (delete)│      │  (replay) │
        └───────────┘    └─────────┘      └───────────┘
```

**The failure policy is the non-obvious part.**

- **5xx / infrastructure exception releases the key.** The failure was transient, so a legitimate
  retry should re-execute. Keeping the key means the client's retry gets a replayed 500 forever —
  the single most common bug in hand-rolled idempotency.
- **4xx keeps the key.** A 400 for a malformed body is deterministic; replaying it is correct and
  cheap.

Configurable via `idempotency.release-on` (`five_xx`, `timeout`).

## The atomic claim

The entire correctness argument rests on one operation: **claim-or-read must be atomic.**

Redis, one round trip and no locks:

```
SET <key> <IN_PROGRESS record> NX EX <ttl>
```

Returns OK (we own the key) or nil (someone else does).

Postgres:

```sql
INSERT INTO idempotency_record (...) VALUES (...)
ON CONFLICT (id) DO UPDATE SET ...
WHERE idempotency_record.expires_at < clock_timestamp()
```

The `WHERE` clause on the `DO UPDATE` is the part worth understanding: it makes the statement
**atomically reclaim expired rows** while still refusing to touch live ones. `rowsAffected == 1`
means we own the key; `0` means a live record exists and should be read instead. This removes any
need for an expiry sweeper on the hot path — the optional background sweeper exists only to
reclaim disk from keys nobody ever retries.

Expiry comparisons use Postgres's own `clock_timestamp()` rather than a JVM-computed `Instant`
sent as a bind parameter. Across multiple application instances with clock skew, comparing against
each instance's own clock would make the same row look expired on one instance and live on
another. `clock_timestamp()` specifically, not `now()`, since the latter freezes at transaction
start.

## Durability, and what "exactly-once" actually means here

There is a window that no store closes for free:

```
BEGIN TX ──► business logic ──► COMMIT ──► [ CRASH WINDOW ] ──► write COMPLETED record
```

If the process dies in that window, the key stays `IN_PROGRESS` until its TTL expires and the
retry re-executes. That is **at-least-once, not exactly-once.**

`JdbcIdempotencyStore.complete()` is `@Transactional(propagation = REQUIRED)`, so it *can* join a
transaction that is already open on the calling thread and commit atomically with the business
data. But that only happens when the application itself calls the store from inside a transaction
it is already holding — for example from a service or repository method that writes the business
row and completes the record before returning.

It does **not** happen via `@Idempotent` + `@Transactional` on the same handler method. The aspect
deliberately wraps *outside* the transactional advisor so that a pure replay never has to open a
database transaction at all, which means `complete()` always runs after the handler's own
transaction has already committed or rolled back. `REQUIRED` then has nothing to join and opens a
new, independent transaction.

**So out of the box, via the annotation alone, both stores are at-least-once.** The README's store
comparison table states that guarantee plainly rather than implying the stronger one.

### Closing the window: `idempotency.jdbc.join-transaction`

0.2 makes the annotation path genuinely exactly-once, behind a property. The aspect wraps
`proceed()` *and* the completion write in a programmatic transaction (`TransactionTemplate`), so the
handler's own `@Transactional(REQUIRED)` joins that instead of opening and closing its own first.
One physical commit, no crash window.

Three things decided its shape:

- **The claim stays outside.** It must commit independently or the `IN_PROGRESS` row would only
  become visible when the handler commits, and every concurrent duplicate would race past it — which
  would defeat the entire library. So a rolled-back transaction leaves an orphaned claim the aspect
  has to `release()` afterwards, including on the `UnexpectedRollbackException` a silent
  `setRollbackOnly()` produces at commit time.
- **It is opt-in, not the default.** Joining changes execution for every advised method, not only
  transactional ones: a handler with no `@Transactional` of its own is pulled into a transaction it
  never asked for, and a handler's own `@Transactional(timeout)` stops applying once it joins. That
  is too large a behaviour change to impose on an upgrade.
- **The failure policy did not change.** A terminal 4xx still gets written and replayed; it is just
  written *after* the rollback, in its own transaction. In the default mode the 4xx record survives
  for exactly the same reason — an independent write — so keeping that shape means the property
  changes atomicity of the success path only, rather than quietly redefining what a 4xx does.

The seam is a one-method `TransactionRunner` interface in `idempotency-core`'s `internal` package,
implemented in `idempotency-store-jdbc`. Core deliberately has no `spring-tx` dependency — the
aspect has to work when the Redis or in-memory store is active and no transaction manager exists —
so it detects `UnexpectedRollbackException` by class name rather than importing it.

## Key composition

The client's header value is **never** used as the storage key directly:

```
storeKey = sha256( namespace | httpMethod | routePattern | clientKey )
```

- `namespace` comes from the resolved `IdempotencyScope` — empty for `GLOBAL`, the principal name
  for `USER`, a JWT claim for `TENANT`, or a custom `ScopeResolver`'s output. Without it, user A's
  key `"abc-123"` collides with user B's.
- `routePattern` comes from `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`, so `/orders` and
  `/refunds` never share a keyspace.

## Fingerprinting

`sha256(canonicalJson(serializableArgs))`. Same key with a different body is a client bug, not a
replay: it returns **422**, never a silent execution of the wrong payload.

Arguments excluded from the fingerprint: `HttpServletRequest`, `HttpServletResponse`, `Principal`,
`Authentication`, `MultipartFile`, `BindingResult`, `Model`, and anything annotated
`@AuthenticationPrincipal`. `@IdempotencyIgnore` lets callers exclude a parameter explicitly.

**Canonicalisation matters.** `{"a":1,"b":2}` and `{"b":2,"a":1}` must hash identically, or every
retry from a client with non-deterministic map ordering fails. The internal `ObjectMapper` is
configured with `ORDER_MAP_ENTRIES_BY_KEYS` and `SORT_PROPERTIES_ALPHABETICALLY`, and is a
dedicated instance — never the application's own, which applications routinely reconfigure.
`IdempotencyObjectMapperCustomizer` is the supported hook for registering custom modules on it.

## Security: `payloadType` is an integrity check, not a deserialization hint

Each stored record carries a `payloadType`. It is **never** used to drive deserialization. Replay
always deserializes into the type resolved from the live method signature at runtime.

Trusting a type name read back out of a store is a classic polymorphic-deserialization gadget
vector: anyone who can write to the store could name any class on the classpath. `payloadType` is
used only as an equality check — if it no longer matches the current method's return type, the
record is treated as stale and the request re-executes, which also handles a method signature
legitimately changing between deploys.
