# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Types of changes:
- Added for new features.
- Changed for changes in existing functionality.
- Deprecated for soon-to-be removed features.
- Removed for now removed features.
- Fixed for any bug fixes.
- Security in case of vulnerabilities.

## [Unreleased]

## [0.4.0] - 2026-07-21

### Changed

- `async`, `blocking`, and `compute` now reserve a leading literal map for future options when body forms follow it. An empty prefix map is accepted and non-empty prefix maps are rejected until option keys are defined. A sole map remains an ordinary body value.

### Removed

- Removed implicit trailing `catch` / `finally` parsing from `async`, `blocking`, `compute`, `async-generator`, `await`, and `wait`. Use ordinary explicit `try` forms; `await` and `wait` now accept exactly one argument.

### Fixed

- Fixed nested `adoseq` and `afor` bindings so they compile and match Clojure's `doseq` and `for` ordering.

## [0.3.0] - 2026-07-18

### Changed

- Promise combinators now consistently observe one result per input. Many-valued channels contribute one next take, `race` leaves losing channel values untouched, `all-settled` observes inputs concurrently while preserving input order, and `timeout` leaves timed-out sources available for later use.
- `time` now treats asynchronous timing as observation-only instrumentation, so its timing callback still runs when borrowed work finishes after the caller completes or is cancelled.

## [0.2.0] - 2026-07-17

### Added

- Added the `IntoPromiseChan` extension protocol and public `->promise-chan` coercion so `Future`, `CompletableFuture`, and `IBlockingDeref` values can be used with async-style APIs.
- Added `detach` for starting background work that should intentionally outlive its current cancellation scope.
- Added `async-generator` and `yield` for creating cold, lossless, core.async-compatible async sources. Generators start on first consumption, are unbuffered by default, support bounded runahead through a positive `:buffer-size`, and cannot yield `nil` because a nil take signals completion.
- Added `anext` for manually consuming one source value and `areturn` for requesting early cleanup and waiting for generator finalization. Calling `cancel!` on an async generator requests the same cleanup without waiting.
- Added `adoseq` and `afor` for lifecycle-aware async iteration with destructuring, nested bindings, and `:let`, `:when`, and `:while` modifiers.
- Added `areduce`, `atransduce`, and `ainto` for lifecycle-aware async reductions and transductions.
- Async iteration consumers accept channel-like sources, Clojure collections, JVM arrays, and `Iterable` values. Generator cleanup and `finally` blocks complete on early exit, errors, and cancellation.

### Changed

- Cancellation now follows ownership. Work started with `async`, `blocking`, or `compute` inside another execution becomes an owned child unless started inside `detach`; cancellation, failure, or normal completion of a parent cancels its unfinished children transitively.
- Awaiting or composing already-started work does not transfer ownership. `timeout`, `race`, `any`, `all`, and `all-settled` observe borrowed inputs without cancelling them, and `race` and `any` do not cancel losing inputs. Work originally started inside the current parent remains owned by that parent.
- `async`, `blocking`, and `compute` now wait for one returned promise-like single-result value before settling their own result promise-chan. Nested async-style producers continue to compose, while ordinary channels and async-generator sources are preserved as channel values.
- `await`, `wait`, `await*`, `wait*`, and the promise combinators now accept values supported by `IntoPromiseChan`.
- Updated the default dependencies to Clojure 1.12.5 and core.async 1.9.865 while retaining compatibility with core.async 1.7.701.

## [0.1.0] - 2025-04-24

### Added

- Initial release
- Core task macros: async, blocking, and compute, each targeting a purpose‑built thread pool (async‑pool, blocking‑pool, compute‑pool).
- Awaiting & blocking helpers: await, wait, await*, and wait* to retrieve asynchronous results with or without throwing.
- Cancellation primitives: cancel!, cancelled?, and check-cancelled! for cooperative cancellation and interrupt propagation.
- Error predicates: error? and ok? to distinguish normal values from Throwable results.
- Promise‑chan combinators: catch, finally, then, chain, and handle for ergonomic error handling and result piping.
- Concurrency utilities: sleep, defer, timeout, race, any, all, and all-settled to orchestrate asynchronous workflows.
- Convenience macros: ado (asynchronous do), alet (asynchronous let), clet (concurrent let with dependency rewriting), and time (wall‑clock timing that understands channels).
- Implicit try/catch/finally support in async, blocking, compute, and await, enabling inline exception handling syntax.
- Java interrupt integration: automatic Thread.interrupt signalling for cancelled blocking and compute tasks where safe.
- Railway‑style result handling via non‑throwing await* / wait* helpers that treat exceptions as values.
- Comprehensive test suite covering all public API functions and macros.
- Extensive README with feature overview, usage examples, and guidance on best‑practice thread‑pool selection.

[unreleased]: https://github.com/xadecimal/async-style/compare/0.4.0...HEAD
[0.4.0]: https://github.com/xadecimal/async-style/compare/0.3.0...0.4.0
[0.3.0]: https://github.com/xadecimal/async-style/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/xadecimal/async-style/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/xadecimal/async-style/tree/0.1.0
