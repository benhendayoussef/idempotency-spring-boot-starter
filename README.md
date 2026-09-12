# idempotency-spring-boot-starter

[![Maven Central](https://img.shields.io/maven-central/v/io.github.benhendayoussef/idempotency-spring-boot-starter.svg)](https://central.sonatype.com/artifact/io.github.benhendayoussef/idempotency-spring-boot-starter)
[![Build](https://github.com/benhendayoussef/idempotency-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/benhendayoussef/idempotency-spring-boot-starter/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Foojay Article](https://img.shields.io/badge/foojay.io-article-orange.svg)](https://foojay.io/today/idempotent-spring-boot-starter/)

A network retry, a double-tapped "Pay" button, or a load balancer replaying a timed-out request
all look identical to your server: a second POST that arrives after the first one already
happened. Without protection, that second request charges the customer twice. One annotation
fixes it:

```java
@Idempotent
@PostMapping("/orders")
public ResponseEntity<OrderResponse> placeOrder(@RequestBody OrderRequest request) {
    return ResponseEntity.status(CREATED).body(orders.charge(request)); // orders: your existing service
}
```

A duplicate request carrying the same `Idempotency-Key` header gets the **original response
replayed** — not a re-execution, not an error.

> Written up on [foojay.io](https://foojay.io/today/idempotent-spring-boot-starter/) if you want the full story behind the design decisions.

## Quickstart (Redis)

```kotlin
dependencies {
    implementation("io.github.benhendayoussef:idempotency-spring-boot-starter:1.0.0") // use the latest published version
    implementation("io.github.benhendayoussef:idempotency-store-redis:1.0.0")
}
```

```java
@Idempotent
@PostMapping("/orders")
public ResponseEntity<OrderResponse> placeOrder(@RequestBody OrderRequest request) { ... }
```

```bash
curl -X POST localhost:8080/orders -H "Idempotency-Key: $(uuidgen)" -d '{"amount":49.99}'
```

Done. Run the same `curl` command again with the same key and you get the same response back,
with an `Idempotent-Replay: true` header, and the handler does not execute a second time.

See [`samples/sample-orders-api`](samples/sample-orders-api) for a runnable demo
(`docker compose up -d && ./gradlew :samples:sample-orders-api:bootRun`, then `./demo.sh`).

## Quickstart (JDBC — Postgres or MySQL)

The JDBC store needs more setup than swapping one dependency — all of the following:

```kotlin
dependencies {
    implementation("io.github.benhendayoussef:idempotency-spring-boot-starter:1.0.0") // use the latest published version
    implementation("io.github.benhendayoussef:idempotency-store-jdbc:1.0.0")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
}
```

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/yourdb
    username: ...
    password: ...
  sql:
    init:
      mode: always
      schema-locations: classpath:db/idempotency/postgres.sql # or mysql.sql - both ship inside idempotency-store-jdbc
idempotency:
  store: jdbc   # required: AUTO never selects JDBC on its own, even with no Redis present
```

For **MySQL or MariaDB**, change three things: the JDBC URL, the driver
(`runtimeOnly("com.mysql:mysql-connector-j")`), and the schema
(`classpath:db/idempotency/mysql.sql`). Nothing else — the dialect is detected from the `DataSource`
on first use, so `idempotency.jdbc.dialect` only needs setting to skip detection.

The same `@Idempotent` annotation and `curl` round trip from the Redis quickstart apply unchanged.

## Quickstart (Caffeine — single instance)

For one instance that stays up, with no Redis and no database, where losing state on restart is
acceptable:

```kotlin
dependencies {
    implementation("io.github.benhendayoussef:idempotency-spring-boot-starter:1.0.0")
    implementation("io.github.benhendayoussef:idempotency-store-caffeine:1.0.0")
}
```

```yaml
idempotency:
  store: caffeine
```

Prefer this over `store=memory` for anything long-lived: the built-in memory store never evicts
expired entries, so it grows for the life of the process.

## What happens

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

The claim-or-read step is atomic (a single `SETNX` for Redis, an `INSERT ... ON CONFLICT` for
Postgres), which is what makes concurrent duplicates safe, not just sequential ones.

**The failure policy is the non-obvious part.** A 5xx / infrastructure failure releases the key —
the failure was transient, so a legitimate retry should re-execute, not replay a stale 500
forever. A 4xx is deterministic, so it's kept and replayed. Configurable via
`idempotency.release-on`.

Same key with a **different request body** is a client bug, not a replay: it gets a `422`, never
a silent execution of the wrong payload.

## Configuration reference

| Property | Default | Notes |
|---|---|---|
| `idempotency.enabled` | `true` | Master switch |
| `idempotency.mode` | `aspect` | `aspect` | `filter`. See [Replay modes](#replay-modes) below |
| `idempotency.filter.replay-headers` | `Content-Type, Location, ETag, Cache-Control` | Headers replayed verbatim in filter mode |
| `idempotency.store` | `auto` | `auto` \| `redis` \| `jdbc` \| `caffeine` \| `memory` |
| `idempotency.default-ttl` | `24h` | How long a **completed response** stays replayable. Overridable per-endpoint via `@Idempotent(ttl = "...")` |
| `idempotency.claim-ttl` | `5m` | How long an **in-flight claim** is held before the holder is presumed dead. Must exceed your slowest handler — [see below](#the-two-ttls) |
| `idempotency.header-name` | `Idempotency-Key` | Overridable via `@Idempotent(keyHeader = "...")` |
| `idempotency.require-key` | `false` | `true` rejects keyless requests with 400 instead of passing them through |
| `idempotency.on-conflict` | `wait` | `wait` polls the in-flight request; `fail_fast` returns 409 immediately |
| `idempotency.wait-timeout` | `5s` | Max poll time before a `wait` conflict gives up with 409 |
| `idempotency.scope` | `global` | `global` \| `user` \| `tenant` \| `custom` (register your own `ScopeResolver`). `user` and `tenant` need Spring Security on the classpath; without it, startup fails with an actionable message rather than at request time |
| `idempotency.tenant-claim` | `tenant_id` | JWT claim read by the built-in TENANT resolver |
| `idempotency.on-missing-principal` | `global` | What a `user`/`tenant`-scoped request does when it reaches the aspect with no resolvable authenticated principal: `global` falls back to the global namespace; `skip` executes unprotected; `reject` returns 400. Never a raw 500 |
| `idempotency.on-store-failure` | `proceed` | `proceed` executes unprotected with a WARN; `fail` returns 503 |
| `idempotency.problem-details` | `true` | Registers the built-in RFC 9457 exception advice |
| `idempotency.max-payload-size` | `256KB` | Larger responses execute normally but aren't cached for replay |
| `idempotency.release-on` | `five_xx,timeout` | Outcomes that release instead of complete the key. Note the underscore: Spring's relaxed binding needs `five_xx`, not `5xx` |
| `idempotency.caffeine.maximum-size` | `10000` | Ceiling on entries held by the Caffeine store; LRU beyond it |
| `idempotency.redis.key-prefix` | `idempotency:` | |
| `idempotency.jdbc.table-name` | `idempotency_record` | |
| `idempotency.jdbc.dialect` | `auto` | `auto` | `postgres` | `mysql`. AUTO detects from the `DataSource` on first use, not at startup |
| `idempotency.jdbc.sweeper-enabled` | `false` | The atomic claim already reclaims expired rows on the hot path; this is only for disk usage |
| `idempotency.jdbc.sweeper-interval` | `15m` | |
| `idempotency.jdbc.join-transaction` | `false` | Run the handler and the completion write in one shared transaction — exactly-once instead of at-least-once. Costs: non-`@Transactional` handlers get pulled into a transaction, and a handler's own `@Transactional(timeout)` stops applying. [Read the caveats first](#opt-in-exactly-once-via-transaction-joining) |
| `idempotency.jdbc.on-silent-rollback` | `return_response` | What a caller gets when a handler marks the shared transaction rollback-only and then returns success. `return_response` sends what the handler returned; `fail` returns 500, because the response describes data that was never committed. Only reachable with `join-transaction=true` |
| `idempotency.metrics.enabled` | `true` | Publish counters to Micrometer when a `MeterRegistry` exists. No effect without one |
| `idempotency.tracing.enabled` | `true` | Tag the request's span with `idempotency.outcome` when a Micrometer Tracing `Tracer` exists. No effect without one |

## The two TTLs

A key has two lifetimes, and conflating them is a trap worth understanding:

| | Governs | Default |
|---|---|---|
| `claim-ttl` | How long an **in-flight** claim is held before the holder is presumed dead | `5m` |
| `default-ttl` | How long a **completed** response stays replayable | `24h` |

Until 0.4 these were one value. That meant a process dying mid-request - a deploy, an OOM, a
scale-down - left its key `IN_PROGRESS` for the whole retention window. With the 24h default, every
retry of that request got a `409` **for a day**, and the sweeper could not help because it only
deletes rows that are already past expiry.

> **`claim-ttl` must be longer than your slowest handler.** If a claim expires while the request is
> still running, a concurrent duplicate reclaims the key and both execute - the exact failure this
> library exists to prevent. The 5-minute default is deliberately generous against typical proxy and
> load-balancer timeouts; raise it if you have handlers that legitimately run longer.

The lease is capped at the retention TTL in effect, so asking for a 30-second idempotency window
never leaves a dead claim sitting for five minutes - and it can never hold a claim *longer* than
0.3 did.

## Store comparison

| | Redis | JDBC (Postgres / MySQL) |
|---|---|---|
| Guarantee | **At-least-once.** If the process crashes between the business transaction committing and the completion record being written, the key stays `IN_PROGRESS` until TTL and a retry re-executes. | **At-least-once by default; exactly-once with `idempotency.jdbc.join-transaction=true`.** Both modes are described below — read them before switching. |
| Speed | Fast — a single round trip per claim. | Slower — shares the datasource and transaction. |
| Setup | `spring-boot-starter-data-redis` | A `DataSource`, `idempotency.store=jdbc`, and the schema for your database applied — see the JDBC quickstart above |
| Good for | The other 95% of use cases. | Payments and anything else where a replayed side effect is unacceptable, **if you use it the way described below.** |

**Measured overhead** (200 requests, 20 warmup iterations, real Redis/Postgres via Testcontainers on
Docker Desktop for Windows): **p50 ~5–6.4ms / p99 up to ~40ms** added by an `@Idempotent` endpoint
versus an identical unannotated one. An in-memory-store control (zero network I/O) measured only
**~0.5ms p50 overhead with a negative p99** (within measurement noise) — the aspect's own cost
(fingerprinting, key composition, JSON (de)serialization, reflection) is sub-millisecond; the rest is
two real network round trips (`claim` + `complete`) plus whatever your Docker/host networking adds.
Expect closer to native-network latency (well under a millisecond of *aspect* overhead) against a
colocated, non-Dockerized Redis/Postgres in production. Don't be misled if you benchmark the same way
and see similar numbers on Docker Desktop — that's the container networking layer, not this library.

### The JDBC store's two modes

#### Default: at-least-once

`JdbcIdempotencyStore.complete()` is `@Transactional(propagation = Propagation.REQUIRED)`: it joins
an *already-open* transaction if one is active on the calling thread, and starts its own if not.
The aspect intentionally wraps *outside* `@Transactional` (so a pure replay never has to open a
database transaction at all), which means that by the time it calls `complete()` — always *after*
your handler has already returned — a `@Transactional` handler's own transaction has already
committed or rolled back. `complete()` then simply starts a **new**, independent transaction of its
own, so it does **not** commit atomically with your business data just because both are annotated.

Two commits, not one. Crash between them and the business data is committed with no completion
record, so the next request with the same key re-executes. That is at-least-once, and it is what you
get out of the box — same as Redis.

The exactly-once guarantee is still available without the property below, but only when *you* call
the store from inside a transaction you're already holding open — e.g. from a repository or service
method that both writes your business row and calls `IdempotencyStore.complete()` before returning.
`@Idempotent` + `@Transactional` on the same method does **not** give you atomicity by itself.

#### Opt-in: exactly-once via transaction joining

```yaml
idempotency:
  store: jdbc
  jdbc:
    join-transaction: true   # default false
```

With this on, the aspect opens a transaction around `pjp.proceed()` **and** the completion write, so
your handler's own `@Transactional(REQUIRED)` *joins* it rather than opening and closing its own
first. One physical commit covers both. There is no window to crash in, and a handler that rolls
back takes the completion record with it — a retry genuinely re-executes instead of replaying a
response that was never committed.

The claim still commits independently, before that transaction opens. It has to: if the
`IN_PROGRESS` row only became visible when your handler committed, every concurrent duplicate would
race straight past it.

**Read this before switching it on.** It changes how *every* `@Idempotent` method executes, not just
transactional ones:

- **A handler with no `@Transactional` of its own now runs inside a transaction**, holding a
  connection for its whole duration. Locking and connection-pool behaviour change. This is the main
  reason the property is opt-in.
- **A handler annotated `@Transactional(REQUIRES_NEW)` opts out of joining**, so atomicity does not
  hold for it: its own transaction commits or rolls back independently while the completion record
  follows the aspect's. That is what `REQUIRES_NEW` means, and the library does not override it.
- **Your handler's own `@Transactional(timeout = ...)` stops applying**, because a joining
  participant cannot widen or narrow the transaction it joined. Configure a timeout on the
  transaction manager instead (`spring.transaction.default-timeout`).
- **The failure policy is unchanged.** 5xx still releases the key; a 4xx is still kept and replayed.
  The 4xx record is written after the rollback in its own transaction, so a retry gets the same
  deterministic client error — but the business data that error described is gone. Don't build a 4xx
  body out of rows written in the same request.
- **A handler that calls `setRollbackOnly()` and then returns a success status is ambiguous**, and
  you choose which half to believe with `idempotency.jdbc.on-silent-rollback`. The default,
  `return_response`, sends what the handler returned — the behaviour in every release so far. Set it
  to `fail` and the caller gets a 500 instead, on the grounds that a `201 Created` describing a row
  that rolled back is worse than an error. Either way the key is released, so a retry re-executes.
  Only reachable in joined mode: without it the handler rolls back its own transaction and the
  library never hears about it.
- **Replays still open zero transactions.** The aspect answers from the store without calling
  `proceed()`, so the transaction manager is never touched — the whole reason for the aspect
  ordering, and asserted directly in the test suite.
- **One connection per in-flight request**, held for the handler's whole duration. The library never
  nests a second transaction inside the first (asserted); the only exception is a handler that asks
  for `REQUIRES_NEW` itself. **Size your connection pool for concurrent in-flight requests, not just
  for query time** — see the measurement below.
- Self-invocation bypasses the proxy here exactly as it does for `@Transactional`: an inner call
  gets neither annotation's behaviour.

**Measured cost of an undersized pool.** 16 concurrent requests, distinct keys, at a handler with no
`@Transactional` of its own that takes 250ms for reasons of its own (an external call, an image
resize) — against a deliberately small **4-connection** pool, real Tomcat and real Postgres, after
warmup:

| Mode | Median | Max | Wall clock for all 16 |
|---|---|---|---|
| Default | ~300–345ms | ~310–415ms | **~310–420ms** |
| `join-transaction=true` | ~835ms | ~1110–1215ms | **~1115–1215ms** |

The unconstrained floor is 250ms. In the default mode the handler holds no connection while it runs,
so all 16 proceed in parallel and the batch lands near the floor. In joined mode each one occupies a
connection for its whole 250ms, so effective concurrency is capped at the pool size: 16 requests ÷ 4
connections × 250ms ≈ 1s, which is what the measurement shows. **No requests failed in either mode**
— the cost is latency, not errors, until the pool's `connection-timeout` is reached.

That ratio is a property of *your* pool size versus *your* concurrency, not a fixed penalty. A pool
sized at or above peak concurrent in-flight requests shows no such gap. Reproduce with
`./gradlew :idempotency-spring-boot-starter:test --tests "*TxJoin*LoadTest*" -i`.

If the property is `true` while the active store is Redis or in-memory, it logs a WARN and no-ops —
it will not break a service that inherits a shared profile. If the store is JDBC and there is no
`PlatformTransactionManager`, startup fails naming the property rather than surprising you at
runtime.

Nobody else publishes this table. If you only remember one thing from this README, it's that
"idempotent" and "exactly-once" are not the same claim, and getting the latter from the JDBC store
takes more than swapping in the dependency.

## Limitations

Being loud about these is what makes a library trustworthy:

- Captures the **method return value**, not raw HTTP bytes (see the design rationale below) —
  anything the handler writes directly to `HttpServletResponse` is not captured.
- Does not work on streaming / SSE / `StreamingResponseBody` returns.
- Does not handle multipart bodies in the fingerprint.
- The JDBC store supports PostgreSQL and MySQL/MariaDB. Other engines need a new dialect.
- `store=memory` never evicts expired entries, so it grows for as long as the process lives. It is
  meant for tests and local development. For a single instance that stays up, use `store=caffeine`,
  which has the same semantics plus real TTL eviction and a size ceiling.
- WebFlux support covers `Mono` only, with a blocking store on `boundedElastic` and `scope=global`. See the WebFlux section.
- AOP-based: self-invocation bypasses the proxy, same as `@Transactional`. A startup check warns
  if `@Idempotent` is found on a non-public method.
- `idempotency.scope` defaults to `global` (no Spring Security needed) precisely so the Quickstart
  boots with zero extra configuration. `user` and `tenant` need Spring Security on the classpath;
  without it present at all, startup fails immediately with an actionable message rather than at
  request time. A specific `user`/`tenant`-scoped request that reaches the aspect unauthenticated
  (e.g. a public endpoint on an app that also has protected ones) is handled per
  `idempotency.on-missing-principal` — never a raw 500.
- Out of the box, both stores are at-least-once. The JDBC store's exactly-once guarantee does not
  come for free from `@Idempotent` + `@Transactional` on the same method — it needs
  `idempotency.jdbc.join-transaction=true`, which has its own tradeoffs. See the two modes under
  Store comparison above.
- **`on-conflict=WAIT` (the default) holds a servlet request thread per waiting duplicate for up to
  `wait-timeout`.** A burst of concurrent duplicates on the same key can measurably slow down
  *unrelated* requests on the same server while the pool is under pressure (observed: an unrelated
  endpoint's latency rose from typical sub-50ms to over a second during a 20-duplicate burst against
  an 8-thread pool in testing, though no requests actually failed at that scale). If you expect
  frequent duplicate bursts under a constrained thread pool, consider `on-conflict=fail_fast` for the
  affected endpoints instead of the default.
- **`on-store-failure=proceed`'s request-latency bound is only as good as your store client's own
  configured timeout** (`spring.data.redis.timeout`, `spring.datasource.hikari.connection-timeout`,
  etc.) — this library adds no timeout of its own. In testing against a store that accepted a
  connection but never responded, the actual observed request latency (~5s) was noticeably higher
  than the configured Lettuce command timeout (1s); investigate your own client's real timeout
  behavior in production rather than assuming it matches the configured number exactly.

**Not a retry library. Not a rate limiter. Not a dedup layer for Kafka consumers.** Scope creep
kills small libraries — these are non-goals forever, not gaps to fill in a future release.

### Why capture the return value instead of raw bytes?

The request body is already parsed into method arguments by the time the handler runs, so the
fingerprint is free — no stream-consumption problems, no custom request-body-caching filter.
Storing the return **object** and re-serializing on replay is also more correct than storing raw
bytes: if the client's `Accept` header differs on the retry, byte replay would send the wrong
content type, while object replay goes through normal `HttpMessageConverter` negotiation.

### Security note

The stored record's `payloadType` is an integrity check, **never a deserialization hint.**
Replay always deserializes into the type resolved from the live method signature, not from a
type name pulled out of the store — trusting a type name from a store is a classic
polymorphic-deserialization gadget vector.

### Further reading

- [`docs/design-decisions.md`](docs/design-decisions.md) — the architectural reasoning: why AOP
  over a servlet filter, the atomic claim, the failure policy, and what "exactly-once" does and
  doesn't mean here.

## Metrics

If your application already has a Micrometer `MeterRegistry` (adding `spring-boot-starter-actuator`
is enough), the starter publishes counters automatically. There is nothing to configure.

Everything lands on **one** counter, `idempotency.requests`, separated by an `outcome` tag:

| `outcome` | Meaning |
|---|---|
| `executed` | Handler ran; the response was stored for replay |
| `replayed` | A duplicate was answered from the store without executing |
| `replayed_after_wait` | A `WAIT`-policy duplicate blocked, then replayed the first call’s response |
| `released` | The key was released so a retry can re-execute (5xx, or a timeout) |
| `conflict` | A duplicate arrived while the first was in flight and got a 409 |
| `wait_timeout` | A `WAIT`-policy duplicate gave up waiting |
| `fingerprint_mismatch` | Same key, different request body - the 422 case |
| `missing_key` | No idempotency key on the request |
| `store_failure` | The store was unreachable |
| `principal_missing` | A `user`/`tenant`-scoped request had no resolvable principal |

One name with a tag rather than ten names is deliberate: it lets you write a replay rate as a single
ratio, and an outcome added in a later version shows up in your existing queries instead of being
invisible until you update them.

```promql
# Replay rate: the share of idempotent traffic served without re-executing
sum(rate(idempotency_requests_total{outcome="replayed"}[5m]))
  / sum(rate(idempotency_requests_total[5m]))
```

Set `idempotency.metrics.enabled=false` to keep the no-op implementation, or register your own
`IdempotencyMetrics` bean to route the same events somewhere else - the starter backs off from both.

## Tracing

A replay is the most confusing span in a distributed trace. The endpoint was called, it returned
`201`, it opened no transaction, issued no query, made no downstream call, and took two
milliseconds. That reads as a handler that silently did nothing.

If your application has a Micrometer Tracing `Tracer` — `spring-boot-starter-actuator` plus a
bridge such as `micrometer-tracing-bridge-otel` or `-brave` — the starter tags the **request's own
span** with what it did:

```
idempotency.outcome = replayed
```

The values are exactly the `outcome` tag values in the table above, so a Prometheus rate and a
trace-search filter select the same population.

The tag goes on the existing server span rather than in a child span of its own. A child span would
put the answer one level down from where you are already looking, add a span to every request in
the system to carry a single string, and still leave the trace list inexplicable to anyone scanning
it. Nothing is added when the request is not sampled.

Set `idempotency.tracing.enabled=false` to leave traces untouched, or register your own
`IdempotencyTracer` bean to send the outcome somewhere else — an OpenTelemetry attribute, an MDC
entry, an audit trail. The starter backs off from both. This works in all three execution paths
(aspect, filter mode and WebFlux); on WebFlux it relies on Reactor context propagation, which Spring
Boot enables when tracing is configured.

## WebFlux

Add `idempotency-webflux` and `@Idempotent` works on reactive handlers that return `Mono`:

```kotlin
dependencies {
    implementation("io.github.benhendayoussef:idempotency-spring-boot-starter:1.0.0")
    implementation("io.github.benhendayoussef:idempotency-webflux:1.0.0")
    implementation("io.github.benhendayoussef:idempotency-store-redis:1.0.0")
}
```

```java
@Idempotent
@PostMapping("/orders")
public Mono<ResponseEntity<OrderResponse>> placeOrder(@RequestBody OrderRequest request) { ... }
```

Nothing else to configure. The servlet and reactive aspects are mutually exclusive by construction,
so an application gets exactly one.

One thing is genuinely better here: `on-conflict=wait` occupies **no thread** while it waits, because
the delay is a timer rather than a sleep. The thread-pool exhaustion documented under Limitations for
the servlet stack does not apply.

Three things to know before adopting it:

- **Only `Mono` is advised.** A `Flux` is a stream, and this library replays a single captured
  response - the same reason the servlet side does not support streaming. A `Flux`-returning handler
  passes through untouched, with a WARN at startup rather than silent half-support.
- **Stores are still blocking**, so store calls are scheduled onto `boundedElastic`. Correct, but an
  idempotent endpoint costs two thread handoffs a plain one does not. A reactive store SPI (R2DBC,
  reactive Redis) would remove that and is not in 0.4.0.
- **`idempotency.scope` must be `global`.** `user` and `tenant` resolve the principal from
  `SecurityContextHolder`, which is a ThreadLocal with no meaning on a reactive stack. Rather than
  quietly falling back to global - which would share idempotency keys across users - startup fails
  with an explanation.
## Replay modes

`idempotency.mode` decides *what* gets stored and replayed. Both modes select endpoints the same
way - `@Idempotent` on the handler - and produce identical storage keys, so switching does not
orphan existing records.

| | `aspect` (default) | `filter` |
|---|---|---|
| Captures | The handler return value, re-serialized on replay | The real HTTP response bytes |
| Body written directly to `HttpServletResponse` | **Not captured** | Replayed exactly |
| Response headers | Rebuilt from the return value | Replayed from an allowlist |
| Argument fingerprinting | Yes - same key, different body gets a 422 | **No** - see below |
| Runs | Inside the handler invocation | Outside the whole dispatch |

Use `filter` when the exact bytes matter: a handler that streams or writes its own response, a
content type negotiated at write time, or a header added by a filter further down the chain. Aspect
mode cannot see any of those, because it returns before the response is written at all.

Two things to know:

- **No argument fingerprinting in filter mode.** That check hashes resolved method arguments, which
  do not exist yet outside the dispatch. The equivalent would be hashing the request body, which
  means buffering every request - a real cost on every endpoint to serve one. So in filter mode a
  duplicate key with a *different* body replays the original response rather than getting a 422.
- **Headers are an allowlist, not everything.** Replaying `Set-Cookie` would hand a second caller
  the first caller's session. Add your own via `idempotency.filter.replay-headers` if clients
  depend on them.

## Operations

### Inspecting and evicting a key

When a process dies mid-request its key stays `IN_PROGRESS` until the [claim lease](#the-two-ttls)
expires, and retries get a `409` until it does. If you need that key back sooner, expose the
management endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,idempotency
```

```bash
# What is holding this key?
curl "localhost:8080/actuator/idempotency?key=abc123&method=POST&route=/orders"
# {"storageKey":"...","found":true,"state":"IN_PROGRESS","createdAt":"..."}

# Let the customer retry now.
curl -X DELETE "localhost:8080/actuator/idempotency?key=abc123&method=POST&route=/orders"
```

Pass the **route pattern**, not the request URI - `/orders/{id}`, not `/orders/42` - and add
`&namespace=alice` for a `user`- or `tenant`-scoped endpoint. Get either wrong and you get a
valid-looking key that addresses nothing, which is why the response tells you plainly when it
evicted nothing.

The stored response body is deliberately **not** returned; the endpoint reports its size instead.
That body is your application’s own response, frequently customer data.

> **This endpoint is sensitive.** Evicting a key lets the next duplicate execute for real, so anyone
> who can reach it can defeat idempotency for a request they can name. Actuator exposes only
> `health` and `info` by default - secure it like any other management endpoint.

### Computing a storage key yourself

`IdempotencyStore.find` and `release` take the hashed storage key. `IdempotencyKeys` derives it, so
you can use the store SPI directly from your own admin tooling or tests:

```java
String storageKey = IdempotencyKeys.storageKey("abc123", "POST", "/orders");
store.find(storageKey).ifPresent(record -> ...);
```

It is the single definition of the key format, shared by the servlet, reactive and filter paths - so
a key means the same thing on every stack.

## Extending

Implement `IdempotencyStore` (four methods: `claim`, `complete`, `release`, `find`) and register
it as a `@Bean` — `@ConditionalOnMissingBean` means yours wins over the built-ins:

```java
@Bean
IdempotencyStore idempotencyStore(/* ... */) {
    return new DynamoDbIdempotencyStore(/* ... */);
}
```

`ScopeResolver` decides the namespace that keeps one caller's key from colliding with another's.
Everything it needs arrives in the `IdempotencyContext` — it never reads ambient state, which is
what lets the same resolver work on any stack:

```java
@Bean
ScopeResolver apiKeyScopeResolver() {
    return new ScopeResolver() {
        @Override public IdempotencyScope supports() { return IdempotencyScope.CUSTOM; }

        @Override public String namespace(IdempotencyContext context) {
            Object auth = context.authentication().orElse(null);
            if (!(auth instanceof MyToken token)) {
                // IllegalStateException specifically: it routes through
                // idempotency.on-missing-principal instead of reaching the caller as a 500.
                throw new IllegalStateException("no API key on this request");
            }
            return token.accountId();
        }
    };
}
```

Other extension points: `IdempotencyMetrics` (wire up Micrometer or anything else),
`IdempotencyTracer` (send the per-request outcome to your own tracing or audit sink), and
`IdempotencyObjectMapperCustomizer` (register your application's Jackson modules on the starter's
internal payload mapper — it deliberately never reuses your app's own `ObjectMapper`).

## API stability

From **1.0.0** this project follows [semantic versioning](https://semver.org), and the point of the
1.0 line is that the list below is a promise rather than an intention.

**Covered — a breaking change here requires a new major version:**

| | |
|---|---|
| `io.github.benhendayoussef.idempotency.api.**` | `@Idempotent` and its attributes, `IdempotencyStore`, `ScopeResolver`, `IdempotencyContext`, `IdempotencyRecord`, `IdempotencyMetrics`, `IdempotencyTracer`, `IdempotencyKeys`, the enums and the exception types |
| Configuration property names and meanings | Every `idempotency.*` key, its default, and what it does |
| **The storage key format** | The hash of namespace, method, route and client key. Changing it would orphan every record already in your store |
| **The stored record format** | A record written by 1.x stays readable by every later 1.x, so a rolling upgrade never strands in-flight keys |
| The actuator endpoint | Its id, parameters and response fields |
| Metric and trace names | `idempotency.requests`, its `outcome` tag values, and the `idempotency.outcome` span tag. Values may be *added*; existing ones will not change meaning |
| Autoconfiguration class names | People name these in `spring.autoconfigure.exclude`, so the names are API even though their `@Bean` methods are not |

**Not covered — these change in any release:**

- **Every `internal` package, in every module.** Each one says so in its own `package-info.java`,
  and a test enforces that they all do. If you are importing from one, you are outside the
  supported API — open an issue and say what you needed, because that is a gap worth closing
  properly.
- The `@Bean` method signatures inside the autoconfiguration classes.
- Log message wording.
- Anything marked `@Deprecated(forRemoval = true)`, after the removal window below.

**Deprecation.** Anything being removed is deprecated in a minor release first, with
`@Deprecated(since = "1.x", forRemoval = true)` and a javadoc pointer to the replacement. It stays
for the remainder of the major version — a minor release never removes covered API.

**Spring Boot.** Both supported generations are exercised by the full test suite in CI on every
push, and dropping one is a major-version change. Java 17 stays the baseline for all of 1.x.

**Adding to an interface.** `IdempotencyMetrics` and `IdempotencyContext` are designed so they can
grow: every method on them has, or can be given, a default body. New outcomes and new context
accessors will arrive in minor releases without breaking implementors. `IdempotencyRecord` is a
record and therefore cannot gain components — if its shape ever has to change, that is a new type,
not a modified one.

## Compatibility matrix

| Starter version | Spring Boot | Java |
|---|---|---|
| 1.0.x | **3.5.x and 4.1.x** | 17+ |
| 0.4.x | **3.5.x and 4.1.x** | 17+ |
| 0.3.x | **3.5.x and 4.1.x** | 17+ |
| 0.2.x | 4.1.x (Spring Framework 7) | 17+ |
| 0.1.x | 4.1.x (Spring Framework 7) | 17+ |

From 0.3.0 there is **one artifact for both Spring Boot generations** - no `-boot3` classifier and no
separate version line. Every Spring API the library uses exists in both, so it is compiled against the
lower bound and the full suite runs against both in CI on every push.

## Roadmap

- **0.2** — ✅ Genuine exactly-once for the JDBC store via `idempotency.jdbc.join-transaction`
- **0.3** — ✅ Spring Boot 3 support, Micrometer metrics, MySQL/MariaDB store, Caffeine store,
  WebFlux (`Mono` handlers), and `mode: filter` for byte-exact replay
- **0.4** — ✅ Operability: a claim lease separate from the retention window, `IdempotencyKeys` for
  addressing a record from your own code, an actuator endpoint to inspect and evict one, and an
  `idempotency.outcome` tag on the request's trace span
- **1.0** — ✅ A stable, supported API. See [API stability](#api-stability)
- **1.1** — A reactive store SPI (R2DBC, reactive Redis), so WebFlux no longer schedules blocking
  store calls onto `boundedElastic`; GraalVM native image hints. Both additive
- **1.2** — Kotlin coroutine support, `@Idempotent` on `@KafkaListener`

## Contributing

Issues and PRs are very welcome. If you've shipped idempotency in production and have thoughts on the failure policy or the exactly-once limitation, open a discussion — that feedback directly shapes the roadmap.

## License

Apache-2.0
