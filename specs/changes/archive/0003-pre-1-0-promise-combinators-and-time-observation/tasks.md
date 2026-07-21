# Tasks

## Promise Combinators

- [x] Prevent `race` from consuming losing channel values.
- [x] Preserve one-next-take behavior for many-value inputs.
- [x] Make `all-settled` observe inputs concurrently.
- [x] Keep `race`, `any`, `all`, and `all-settled` observation-only.
- [x] Keep `timeout` observation-only for raw and lifecycle-aware sources.

## Timing And Documentation

- [x] Make async `time` observation detached from the caller's scope.
- [x] Update public documentation and generated docstrings.
- [x] Add behavioral tests for combinator and timing semantics.
- [x] Fold the implemented behavior into `specs/current`.
- [x] Preserve the completed change in the archive.
