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

### Added

- Added `IntoPromiseChan` and public `->promise-chan` support for observing `Future`, `CompletableFuture`, and `IBlockingDeref` values as async-style promise-chans.
- Added public `detach` as the explicit escape hatch for background work that should outlive the current cancellation scope.
- Added structured ownership tracking for `async`, `blocking`, and `compute` executions started inside another execution.
- Added cancellation tests for direct and transitive parent-child propagation, parent completion/failure cleanup, borrowed observation, and detached work.
- Added `bb test-vars` for running specific tests against both the default `core.async` version and the `:test-1.7` compatibility profile.
- Added `:dev` alias with `-Djdk.attach.allowAttachSelf`.

### Changed

- Cancellation now follows ownership, not observation.
- Starting `async`, `blocking`, or `compute` inside a cancellation scope now creates a direct owned child unless started inside `detach`.
- Parent cancellation now cancels unfinished direct children; each child then cancels its own children transitively.
- Parent normal completion and parent failure now cancel unfinished owned direct children.
- `race`, `any`, `all`, `all-settled`, and `timeout` observe borrowed inputs without taking ownership of or cancelling those inputs.
- `race` and `any` no longer cancel losing inputs merely because another input settled first.
- Locally started work passed to `race` / `any` / `all` remains owned by its parent scope and may be cancelled when that parent settles.
- Producer settlement now waits one level when an `async`, `blocking`, or `compute` body returns a supported async value, so nested async-style producers compose while raw channels yielding channels are not recursively flattened.
- `await`, `wait`, `await*`, and `wait*` now accept values supported by `IntoPromiseChan` and rely on producer-side settlement rather than recursive consumer-side joining.
- `Future`, `CompletableFuture`, and `IBlockingDeref` coercion now happens in a detached blocking task so observing borrowed async values does not create ownership in the current scope.
- `race`, `any`, and `all` were refactored to use lightweight input observation and distributed completion accounting while keeping borrowed inputs observation-only.
- `all` now stores results in an atom-backed vector for thread-safe concurrent observer updates.
- `bb release` now runs `bb gen` before tests and install.
- Updated Clojure to `1.12.3`; core.async remains at `1.8.741` with compatibility testing against core.async `1.7.701`.

### Fixed

- Fixed nested `async` children not being registered with their parent cancellation scope.
- Fixed parent cancellation not propagating to plain nested `async` children.
- Fixed unfinished owned children being left running after parent completion or failure.
- Fixed stale documentation and tests that claimed `race` / `any` cancel loser inputs.
- Fixed auto-coercion gaps across helpers for futures, completable futures, and blocking deref values.
- Fixed timing-sensitive compute cancellation coverage by ensuring the tested compute task is queued before cancellation.

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

[unreleased]: https://github.com/xadecimal/async-style/compare/0.1.0...HEAD
[0.1.0]: https://github.com/xadecimal/async-style/tree/0.1.0
