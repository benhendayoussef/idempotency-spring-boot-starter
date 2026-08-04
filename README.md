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
    implementation("io.github.benhendayoussef:idempotency-spring-boot-starter:0.1.0") // use the latest published version
    implementation("io.github.benhendayoussef:idempotency-store-redis:0.1.0")
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

## Quickstart (JDBC / Postgres)

The JDBC store needs more setup than swapping one dependency — all of the following:

```kotlin
dependencies {
    implementation("io.github.benhendayoussef:idempotency-spring-boot-starter:0.1.0") // use the latest published version
    implementation("io.github.benhendayoussef:idempotency-store-jdbc:0.1.0")
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
      schema-locations: classpath:db/idempotency/postgres.sql # ships inside idempotency-store-jdbc
idempotency:
  store: jdbc   # required: AUTO never selects JDBC on its own, even with no Redis present
```

The same `@Idempotent` annotation and `curl` round trip from the Redis quickstart apply unchanged.

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
| `idempotency.store` | `auto` | `auto` \| `redis` \| `jdbc` \| `memory` |
| `idempotency.default-ttl` | `24h` | Overridable per-endpoint via `@Idempotent(ttl = "...")` |
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
| `idempotency.redis.key-prefix` | `idempotency:` | |
| `idempotency.jdbc.table-name` | `idempotency_record` | |
| `idempotency.jdbc.sweeper-enabled` | `false` | The atomic claim already reclaims expired rows on the hot path; this is only for disk usage |
| `idempotency.jdbc.sweeper-interval` | `15m` | |

## Store comparison

| | Redis | JDBC (Postgres) |
|---|---|---|
| Guarantee | **At-least-once.** If the process crashes between the business transaction committing and the completion record being written, the key stays `IN_PROGRESS` until TTL and a retry re-executes. | **At-least-once via `@Idempotent` alone — the same as Redis.** See the caveat below for the narrower case where JDBC is genuinely exactly-once. |
| Speed | Fast — a single round trip per claim. | Slower — shares the datasource and transaction. |
| Setup | `spring-boot-starter-data-redis` | A `DataSource`, `idempotency.store=jdbc`, and `db/idempotency/postgres.sql` applied — see the JDBC quickstart above |
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

### The JDBC store's exactly-once caveat

`JdbcIdempotencyStore.complete()` is `@Transactional(propagation = Propagation.REQUIRED)`: it joins
an *already-open* transaction if one is active on the calling thread, and starts its own if not.
The aspect intentionally wraps *outside* `@Transactional` (so a pure replay never has to open a
database transaction at all), which means that by the time it calls `complete()` — always *after*
your handler has already returned — a `@Transactional` handler's own transaction has already
committed or rolled back. `complete()` then simply starts a **new**, independent transaction of its
own, so it does **not** commit atomically with your business data just because both are annotated.

The exactly-once guarantee is real, but only when *you* call the store from inside a transaction
you're already holding open — e.g. from a repository or service method that both writes your
business row and calls `IdempotencyStore.complete()` (or relies on the same connection/transaction
context) before returning. Relying on `@Idempotent` + `@Transactional` on the same method to give
you atomicity "for free" does not currently work; treat the JDBC store as at-least-once, same as
Redis, unless you've verified your own call site joins the transaction directly.

Nobody else publishes this table. If you only remember one thing from this README, it's that
"idempotent" and "exactly-once" are not the same claim, and getting the latter from the JDBC store
takes more than swapping in the dependency.

## Limitations (v0.1)

Being loud about these is what makes a library trustworthy:

- Captures the **method return value**, not raw HTTP bytes (see the design rationale below) —
  anything the handler writes directly to `HttpServletResponse` is not captured.
- Does not work on streaming / SSE / `StreamingResponseBody` returns.
- Does not handle multipart bodies in the fingerprint.
- Postgres only for the JDBC store (MySQL is on the roadmap).
- Servlet stack only (WebFlux is on the roadmap).
- AOP-based: self-invocation bypasses the proxy, same as `@Transactional`. A startup check warns
  if `@Idempotent` is found on a non-public method.
- `idempotency.scope` defaults to `global` (no Spring Security needed) precisely so the Quickstart
  boots with zero extra configuration. `user` and `tenant` need Spring Security on the classpath;
  without it present at all, startup fails immediately with an actionable message rather than at
  request time. A specific `user`/`tenant`-scoped request that reaches the aspect unauthenticated
  (e.g. a public endpoint on an app that also has protected ones) is handled per
  `idempotency.on-missing-principal` — never a raw 500.
- The JDBC store's exactly-once guarantee does not come for free from `@Idempotent` +
  `@Transactional` on the same method — see the caveat under Store comparison above. Out of the
  box, both stores are at-least-once.
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
- [`docs/explained/`](docs/explained/00-overview.md) — a class-by-class walkthrough of the
  entire codebase.

## Extending

Implement `IdempotencyStore` (four methods: `claim`, `complete`, `release`, `find`) and register
it as a `@Bean` — `@ConditionalOnMissingBean` means yours wins over the built-ins:

```java
@Bean
IdempotencyStore idempotencyStore(/* ... */) {
    return new DynamoDbIdempotencyStore(/* ... */);
}
```

Other extension points: `ScopeResolver` (custom key namespacing), `IdempotencyMetrics` (wire up
Micrometer or anything else), and `IdempotencyObjectMapperCustomizer` (register your application's
Jackson modules on the starter's internal payload mapper — it deliberately never reuses your
app's own `ObjectMapper`).

## Compatibility matrix

| Starter version | Spring Boot | Java |
|---|---|---|
| 0.1.x | 4.1.x (Spring Framework 7) | 17+ |

## Roadmap

- **0.2** — Genuine exactly-once for the JDBC store when `@Idempotent` + `@Transactional` are on
  the same method (transaction-joining the completion write instead of always opening an
  independent one), MySQL store, `mode: filter` for byte-exact replay, Micrometer metrics
- **0.3** — WebFlux support, Caffeine store for single-instance apps
- **0.4** — GraalVM native image hints
- **0.5** — Kotlin coroutine support, `@Idempotent` on `@KafkaListener`

## Contributing

Issues and PRs are very welcome. If you've shipped idempotency in production and have thoughts on the failure policy or the exactly-once limitation, open a discussion — that feedback directly shapes the roadmap.

## License

Apache-2.0
