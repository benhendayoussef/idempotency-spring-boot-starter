# Changelog

All notable changes to this project are documented in this file.

## [1.0.0] - 2026-09-12

**The API is now stable.** Everything in `io.github.benhendayoussef.idempotency.api`, every
`idempotency.*` property name and meaning, the storage key format, the stored record format, the
actuator endpoint, and the metric and trace names are covered by semantic versioning from here:
breaking any of them requires a 2.0. The README's **API stability** section is the full list,
including what is deliberately *not* covered - every `internal` package, in every module.

No new features. 1.0 is a commitment, not a feature release; the one behavioural change below is
an API fix made now precisely because it could not be made later.

### Changed

- **Breaking: `ScopeResolver.namespace()` now takes an `IdempotencyContext`.** It previously took
  no arguments and implementations read `SecurityContextHolder` directly. That single decision is
  why `idempotency.scope` has always been restricted to `global` on WebFlux - the ThreadLocal is
  empty on a reactive stack, and falling back to global silently would have shared idempotency keys
  across users. Handing the resolver its inputs makes the SPI stack-agnostic and testable without
  standing up a security context, and means reactive scoping can be added later as a purely
  additive change rather than a second parallel SPI.

  **Migration.** Only affects `idempotency.scope=custom`, or an application that registered its own
  bean to override a built-in resolver. Add the parameter and take the authentication from the
  context instead of the ThreadLocal:

  ```java
  // before
  public String namespace() {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      return auth.getName();
  }

  // after
  public String namespace(IdempotencyContext context) {
      Object auth = context.authentication().orElse(null);
      if (!(auth instanceof Authentication authentication)) {
          throw new IllegalStateException("no principal");   // routed through on-missing-principal
      }
      return authentication.getName();
  }
  ```

  `IdempotencyContext` also carries `clientKey()`, `httpMethod()` and `routePattern()`, none of
  which a resolver could see before. It is an interface rather than a record specifically so it can
  gain accessors in a minor release without breaking implementors.

### Added

- A published **API stability policy** (README), saying exactly what semantic versioning covers
  here. Two entries are unusual enough to call out: the **storage key format** and the **stored
  record format**. Changing either would orphan records already in your store or strand in-flight
  keys during a rolling upgrade, so both are treated as API even though neither is a Java type.
- `SECURITY.md`, with private vulnerability reporting and - more usefully - what actually counts as
  a vulnerability in a library like this one. Cross-caller replay and key collision are the real
  threat classes; a replay to the same caller with the same key is the product working.

### Internal

- A `testNoSpringSecurity` task runs part of the suite with the Spring Security jars genuinely
  removed from the classpath. `idempotency-core` compiles against Spring Security but must never
  link to it for a `scope=global` application, and that failure would be a `NoClassDefFoundError`
  on the first request rather than at startup - invisible to every context test. Runs in CI on both
  Boot generations.

## [0.4.0] - 2026-09-11

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
- **Tracing.** When the application has a Micrometer Tracing `Tracer`, the request's own span is
  tagged `idempotency.outcome` with what the library did. A replayed request - 201, no query, no
  downstream call, two milliseconds - was previously indistinguishable in a trace from a handler
  that silently did nothing. The values are the same as the `outcome` tag on
  `idempotency.requests`, so a metric and a trace filter select the same population. Turn it off
  with `idempotency.tracing.enabled=false`, or replace it with an `IdempotencyTracer` bean. Applies
  to the aspect, filter mode and WebFlux alike.
- `idempotency.jdbc.on-silent-rollback` - what a caller gets when a handler marks the shared
  transaction rollback-only and then returns a success status. The default, `return_response`,
  sends what the handler returned and keeps the behaviour of every release so far; `fail` returns
  500 instead, on the grounds that a `201 Created` describing a row that rolled back misleads the
  caller. The key is released either way, so a retry re-executes. Open since 0.2, where the choice
  was made silently; only reachable with `join-transaction=true`.
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
