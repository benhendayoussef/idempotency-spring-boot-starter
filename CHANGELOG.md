# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

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

## [0.1.0] - Unreleased

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
  open. Transaction-joining the completion write automatically is targeted for 0.2.
- `on-conflict=WAIT` under a large concurrent duplicate burst can measurably degrade whole-application
  thread-pool responsiveness, not just the affected endpoint.
- `on-store-failure=proceed`'s worst-case request latency depends entirely on the consumer's own
  store-client timeout configuration, and was observed to exceed it in testing.
- Servlet stack only; Postgres only for the JDBC store. See the README Roadmap for what's planned.
