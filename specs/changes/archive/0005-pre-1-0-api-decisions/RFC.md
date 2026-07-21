# RFC 0005 - Pre-1.0 API Decisions

## Status

Completed

## Summary

Record several pre-1.0 API decisions that began as future ideas and were
completed without separate focused RFCs: retaining the root namespace,
reserving execution option maps, keeping `time` observation-only, and
documenting Promise-combinator lifecycle boundaries.

## Motivation

These decisions shaped the implemented public contract and need a durable
historical record after `/specs/current` became the canonical description of
the system.

## Scope

- Root namespace organization.
- Prefix execution option-map reservation.
- Observation semantics for `time`.
- Promise-combinator and async-source lifecycle documentation.

## Out Of Scope

- Defining non-empty execution option keys.
- Splitting the public namespace.
- Adding new many-value operators.

## Current Behavior At Completion

All decisions below were implemented before this retrospective RFC was
normalized into the archive.

## Specification Changes

The final behavior is described in:

- `specs/current/architecture/overview.md`
- `specs/current/api/execution-and-cancellation.md`
- `specs/current/api/promise-composition.md`

## Compatibility

The option-map reservation rejects previously ambiguous leading non-empty maps
in multi-form execution bodies. The other recorded decisions clarify or
preserve existing behavior.

## Acceptance Criteria

- [x] The root namespace remains the public entry point.
- [x] Leading literal option maps are reserved and validated.
- [x] `time` observes borrowed work without taking ownership.
- [x] Promise-style and lifecycle-aware APIs have explicit boundaries.
- [x] Implemented behavior is represented in `/specs/current`.

## Completed Decisions

These sections preserve ideas moved out of `specs/future-ideas.md` after their
requested work was completed. Future extensions mentioned inside a completed
decision remain active only when they are still listed in
`specs/future-ideas.md`.

## Root Namespace Organization

Completed for the pre-1.0 API.

The root namespace remains convenient, with a conceptual distinction between:

- Single-result async values and Promise-style combinators.
- Many-value channel/lifecycle APIs such as `async-generator`, `anext`,
  `areturn`, `adoseq`, `afor`, `areduce`, `atransduce`, and `ainto`.

A physical namespace split was intentionally not introduced merely for
tidiness. A possible later split remains in `specs/future-ideas.md` if the
many-value API eventually makes the root namespace hard to scan.

## Prefix Options Map Reservation

Completed before 1.0.

`async-generator` accepts a prefix options map for generator-specific options
such as `:buffer-size`.

The prefix position is also reserved on `async`, `blocking`, and `compute` when
a literal leading map is followed by at least one body form. A sole map remains
an ordinary body value, so `(async {})` returns an empty map. No execution
option keys are implemented yet: an empty prefix map is accepted and any
non-empty prefix map is rejected at macro expansion.

This reservation makes future option keys additive without silently ignoring
unsupported keys. Code that intends to evaluate a map first in a multi-form
body must make that intent explicit, for example:

```clojure
(async
  (do {:status :starting})
  (do-work))
```

The decision also rejected suffix options because a suffix map is ambiguous
with an ordinary map result.

```clojure
(async
  (do-work)
  {:status :ok})
```

## Instrumentation Semantics For `time`

Completed by the pre-1.0 Promise combinator and `time` observation RFC.

The original question was whether instrumentation should remain strictly
observation-only inside an active cancellation scope. Owned callback work could
otherwise make timing output disappear after parent completion or cancellation.

`time` is observation-only instrumentation. Its callback is detached from the
caller's cancellation scope, so parent completion or cancellation does not
suppress timing output for borrowed work. Timing does not close, cancel,
lifecycle-return, or otherwise manage the observed expression.

## Promise Combinator And Lifecycle Documentation

Completed alongside the pre-1.0 combinator implementation.

Public documentation and docstrings now make these boundaries explicit:

- Promise-style combinators are single-result APIs.
- Many-valued channel inputs contribute one next take.
- Promise-style combinators do not call `areturn`, `close!`, or `cancel!` on
  input sources.
- `anext` takes one raw source value and does not imply lifecycle cleanup.
- `areturn` is the explicit early lifecycle cleanup API.
- `timeout` applies to one result, not to the lifetime of a source.
- Structured concurrency still cleans up owned generator producers when the
  owning scope ends.
