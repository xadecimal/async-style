# Architecture Overview

## Purpose

`async-style` is a Clojure library that provides async/await-style execution,
Promise-style composition, structured cancellation, and lifecycle-aware async
iteration on top of `core.async`.

The library has no persistence layer, network service, or application domain.
Its implemented behavior is the concurrency API described under
`specs/current/api`.

## Source Layout

- `src/com/xadecimal/async_style/impl.clj` contains the runtime and macro
  implementation.
- `src/com/xadecimal/async_style/protocols.clj` defines extension protocols,
  currently `IntoPromiseChan`.
- `src/com/xadecimal/async_style.clj` is the generated public namespace.
- `test/com/xadecimal/async_style_test.clj` contains behavioral and
  compatibility tests and serves as executable usage documentation.

Public exports and the public namespace docstring are configured in
`build.clj`. Changes to exported vars or docstrings are made in implementation
sources and propagated with `bb gen`; the generated namespace is not edited by
hand.

## Runtime Model

Single-result async-style operations use `core.async` promise channels. A
successful value or `Throwable` is delivered as the result; APIs such as
`await` and `wait` rethrow `Throwable` values while their `*` variants preserve
them as values.

`async`, `blocking`, and `compute` select execution contexts for async control
flow, blocking work, and CPU-heavy computation respectively. `async` remains a
`core.async` IOC `go` block on its platform-thread dispatch executor.
`blocking` uses `io-thread` where available, which means one virtual thread per
task with current `core.async` on Java 21+, and a cached platform-thread
executor otherwise. `compute` uses Clojure's fixed Agent pooled executor.
Executor customization can change the exact underlying executors without
changing those workload contracts.

Multi-result async generators use a channel-compatible wrapper with internal
lifecycle state. They remain usable with ordinary `core.async` take and close
operations while exposing async-style start, return, cancellation, and
finalization behavior internally.

## Concurrency Boundaries

Starting an execution creates an ownership edge from the current execution
scope unless the start occurs inside `detach`. Observing already-started work
does not create an ownership edge. This distinction governs cancellation and
cleanup throughout the API.

The implementation supports both the default `core.async` dependency and the
compatibility version selected by the `:test-1.7` alias.
