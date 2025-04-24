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
