# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### Fixed

- **A process that died mid-request locked its idempotency key for the full retention window** -
  24 hours by default. The claim and the completed record shared one TTL, so every retry of that
  request got a 409 for a day, and the expired-row sweeper could not help because it only deletes
  rows already past expiry. `idempotency.claim-ttl` (default `5m`) now governs the in-flight claim
  separately.

  **Behaviour change.** `claim-ttl` must be longer than your slowest handler: a lease expiring
  mid-flight lets a concurrent duplicate reclaim the key and both execute. The default is generous
  against typical proxy timeouts, and the lease is capped at the retention TTL in effect - so it can
  never hold a claim longer than 0.3 did. Raise it if you have handlers that legitimately run for
  more than five minutes.

### Added

- `IdempotencyKeys` - supported API for deriving the storage key that `IdempotencyStore.find` and
  `release` take. Both methods were public but their key could only be computed by internal code,
  which made them unusable from outside exactly when you needed them. It is now the single
  definition of the key format, shared by the servlet, reactive and filter paths.
- An actuator endpoint (`/actuator/idempotency`) to inspect or evict a single key - the remedy for
  a claim stuck `IN_PROGRESS`. Reports the stored payload’s size rather than its content, and stays
  unreachable until explicitly exposed, since evicting a key defeats idempotency for a named
  request.
- Every `internal` package now declares that it is not supported API, guarded by a test.
  `store/caffeine/internal`, `webflux/internal` and `internal/filter` shipped in 0.3 without one -
  the last because Java does not inherit `package-info` into subpackages.

## [0.3.0] - 2026-08-28

### Added

- **Spring Boot 3 support.** One artifact now works on Boot 3.5.x and 4.1.x - no `-boot3`
  classifier and no separate version line. Every Spring API the library uses exists in both, so it
  is compiled against the lower bound and the full suite runs against both generations in CI.
- MySQL and MariaDB support for the JDBC store, selected by `idempotency.jdbc.dialect` (`auto` by
  default, detected from the `DataSource` on first use). Schema ships as
  `db/idempotency/mysql.sql`. The full behavioural matrix, including the concurrency soak, runs
  against a real MySQL.
- Micrometer metrics, wired automatically when the application has a `MeterRegistry`. Every outcome
  lands on one counter, `idempotency.requests`, tagged by `outcome` - so a replay rate is a single
  ratio rather than a hard-coded list of metric names. `idempotency.metrics.enabled=false` opts out;
  a user-supplied `IdempotencyMetrics` bean still wins.
- `idempotency-store-caffeine`: a single-instance store with real TTL eviction and a bounded size
  (`idempotency.caffeine.maximum-size`). The built-in `store=memory` treats expired entries as
  absent but never removes them, so it grows for the life of the process - fine for tests, a slow
  leak for a service that stays up.
- `idempotency-webflux`: `@Idempotent` on reactive handlers returning `Mono`. The WAIT policy holds
  no thread on this stack. Limitations, all documented: `Mono` only, blocking stores scheduled onto
  `boundedElastic`, and `idempotency.scope=global` only - a non-global scope fails startup rather
  than silently sharing keys across users.
- `idempotency.mode=filter`: byte-exact replay. Stores the real HTTP response - status, allowlisted
  headers and body bytes - instead of the handler return value, so a body written straight to the
  `HttpServletResponse` replays exactly. Endpoint selection stays annotation-driven and storage keys
  are identical to aspect mode, so switching does not orphan existing records. Argument
  fingerprinting is not available in this mode.

### Fixed

- The starter-internal payload `ObjectMapper` could win Spring Boot's
  `@ConditionalOnMissingBean(ObjectMapper.class)` race, making Boot back off entirely — so the
  library's private mapper silently became the mapper the whole application serialized every HTTP
  response with, and any `IdempotencyObjectMapperCustomizer` leaked into the application's own wire
  format. Present in 0.1 and 0.2. The autoconfiguration is now ordered after Jackson's, and both
  injection points bind by qualifier.
- `IdempotencyAutoConfiguration` was gated entirely on a servlet web application, so everything in
  it - store selection, the payload mapper, fingerprinting, metrics - was unavailable to any
  non-servlet stack. Only the servlet aspect needed that condition.

## [0.2.0] - 2026-08-23

### Added

- `idempotency.jdbc.join-transaction` (boolean, default `false`). When enabled, the aspect runs the
  handler and the completion write in one shared transaction, so they commit or roll back together —
  genuine exactly-once for the JDBC store via `@Idempotent` alone, closing the crash window
  documented as a 0.1 limitation ([#1](https://github.com/benhendayoussef/idempotency-spring-boot-starter/issues/1)).
  Default behaviour is unchanged; see the README's "The JDBC store's two modes" for the tradeoffs
  the property brings with it.

### Changed

- The JDBC store's Gradle module description and the `idempotency.store=jdbc` configuration-metadata
  hint no longer claim unconditional exactly-once; both now name the property that provides it.

## [0.1.0] - 2026-08-02

First public release.

### Added

- `@Idempotent` annotation for HTTP request replay: claims a key atomically, executes the handler
  once, replays the stored response for every duplicate.
- Redis (`idempotency-store-redis`) and JDBC/Postgres (`idempotency-store-jdbc`) `IdempotencyStore`
  implementations, plus an in-memory store for local development and tests.
- Configurable conflict handling (`WAIT` / `FAIL_FAST`), failure policy (`FIVE_XX` / `TIMEOUT`
  release triggers), and store-unavailable handling (`PROCEED` / `FAIL`).
- `GLOBAL` / `USER` / `TENANT` / `CUSTOM` key scoping via the `ScopeResolver` extension point, with
  a startup-time fail-fast check when the configured scope has no matching resolver, and a
  request-time `idempotency.on-missing-principal` (`global` / `skip` / `reject`) policy for
  `USER`/`TENANT`-scoped requests that reach the aspect unauthenticated.
- RFC 9457 `ProblemDetail` responses for every library exception, including a `Retry-After` header
  on 409 conflicts derived from `idempotency.wait-timeout`.
- `IdempotencyMetrics` and `IdempotencyObjectMapperCustomizer` extension points.
- Startup validation: warns on `@Idempotent` methods that are non-public (silently bypass the AOP
  proxy).

### Known limitations (see README for full detail)

- The JDBC store is at-least-once via `@Idempotent` alone; genuine exactly-once requires the
  application to call `IdempotencyStore.complete()` from inside a transaction it already holds
  open. (Resolved in 0.2.0 by `idempotency.jdbc.join-transaction`.)
- `on-conflict=WAIT` under a large concurrent duplicate burst can measurably degrade whole-application
  thread-pool responsiveness, not just the affected endpoint.
- `on-store-failure=proceed`'s worst-case request latency depends entirely on the consumer's own
  store-client timeout configuration, and was observed to exceed it in testing.
- Servlet stack only; Postgres only for the JDBC store. See the README Roadmap for what's planned.
