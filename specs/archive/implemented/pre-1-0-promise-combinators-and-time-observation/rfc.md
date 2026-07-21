# RFC: Pre-1.0 Promise Combinator And Time Observation Fixes

## Status

Implemented.

## Summary

Before 1.0, tighten existing public API semantics in two places:

1. Promise-style combinators should preserve JS Promise behavior while giving
   many-valued channel inputs precise one-next-take semantics.
2. `time` should be observation-only instrumentation. Its timing callback should
   not become owned work in the caller's cancellation scope.

This RFC requests behavior changes to existing APIs. It does not add new public
APIs.

Related context:

- `specs/archive/superseded/promise-combinator-next-take/rfc.md`
- `specs/future-ideas.md`

## Goals

- Keep `race`, `any`, `all`, `all-settled`, and `timeout` Promise-style
  single-result combinators.
- Preserve existing Promise-like behavior for promise-chans, futures,
  completable futures, and immediate values.
- Treat many-valued channel inputs as one next-take result.
- Avoid implicit `areturn`, `close!`, or `cancel!` on input sources.
- Fix `race` so it cannot consume a losing many-valued channel's next value.
- Make `all-settled` observe inputs concurrently, matching Promise-style
  eagerness.
- Fix `timeout` so timing out a next take does not make a lifecycle-aware source
  owned by an internal timeout waiter.
- Make `time`'s timing side effect observation-only/detached from the caller's
  cancellation scope.

## Non-Goals

- Do not add stream operators.
- Do not rename Promise-style combinators.
- Do not introduce JS-style `Promise.allSettled` result maps.
- Do not add stream-specific done statuses.
- Do not change `areduce`, `atransduce`, or `ainto` synchronous reducer
  semantics.
- Do not physically split namespaces.

## Promise Combinator Contract

Promise-style combinators operate on one result per input.

For promise-like inputs, that result is the settled value.

For many-valued channel inputs, that result is one next take. The combinator
does not infer that the caller is done with the source. It must not call
`areturn`, `close!`, or `cancel!` on input sources as part of normal
observation.

If a lifecycle-aware source is started by the caller's current scope, normal
structured concurrency still applies. Ending the owning scope cancels unfinished
owned producer work. That cleanup is a property of ownership, not a lifecycle
side effect of the combinator.

## Requested Changes

### `race`

Current problem: `race` observes channel inputs through one observer per input.
When many-valued inputs are ready around the same time, more than one input can
be consumed even though only one value wins.

Requested behavior:

- First observed result wins, ok or error.
- Many-valued channel inputs contribute at most one next take.
- Across all many-valued inputs, `race` consumes exactly one winning result.
- Losing many-valued inputs are not consumed.
- Inputs are not closed, cancelled, or returned.
- Promise-like behavior remains unchanged.

### `any`

Current validation showed expected one-take-per-input behavior in checked cases.
No implementation change is required unless new tests expose a counterexample.

Requested behavior to lock down:

- First ok result wins.
- Error results are observed and collected.
- Each many-valued input contributes at most one next take.
- Ignoring an error result does not continue consuming from the same input.
- Inputs are not closed, cancelled, or returned.

### `all`

Current behavior already observes inputs concurrently and preserves input order.
No implementation change is required unless new tests expose a counterexample.

Requested behavior to lock down:

- Observe one result from each input.
- Return ok results in input order.
- Settle with the first observed error result.
- Do not close, cancel, or return other inputs when one input errors.

### `all-settled`

Current problem: `all-settled` currently observes inputs sequentially.

Requested behavior:

- Observe one result from each input concurrently.
- Return a vector of all results in input order.
- Preserve current async-style error-as-value behavior.
- Do not short-circuit.
- Do not close, cancel, or return inputs.
- Preserve empty and nil input behavior returning an empty vector.

### `timeout`

Current problem: `timeout` waits through an internal child `async`. With a cold
lifecycle-aware source, timing out cancels that child waiter; because the source
producer was started under that waiter, cleanup runs. This is structured and
leak-free, but it violates the stricter Promise-combinator rule that timeout
cancels only its own pending wait machinery, not input source lifecycle.

Requested behavior:

- Wait for one result from the input before the deadline.
- On timeout, settle with the timeout value or `TimeoutException`.
- Cancel only internal timer/wait bookkeeping.
- Do not close, cancel, or return the input source.
- A raw many-valued channel remains usable after timeout.
- A lifecycle-aware source should not be returned or producer-cancelled merely
  because timeout elapsed.
- Promise-like behavior remains unchanged.

## `time` Contract

`time` is instrumentation. Timing an async expression should observe completion
and run the timing side effect without becoming meaningful child work of the
caller's current cancellation scope.

Requested behavior:

- Preserve the public API and return value.
- If `expr` returns a channel-like value, still return that original value.
- Run `print-fn` after the channel-like value produces its result.
- The timing callback should be detached or otherwise observation-only.
- Parent cancellation/completion should not suppress the timing callback merely
  because the callback was registered inside the parent scope.
- Timing should not cancel, close, return, or otherwise manage the observed
  expression.

Acceptable implementation strategies:

- Wrap the callback attachment in `detach`.
- Or replace the current callback path with lighter observation machinery that
  does not register as owned child work.

## Tests

Add focused tests before changing implementation where practical.

### Promise Combinators

Cover both raw many-valued channels and lifecycle-aware `AsyncStyleChannel`
sources.

Required cases:

- `race` consumes exactly one result total from ready many-valued channels.
- `race` does not consume a losing many-valued channel's next value.
- `race` with a lifecycle-aware source does not call `areturn`.
- `any` consumes at most one value per many-valued input.
- `all` observes many-valued inputs concurrently and preserves order.
- `all` does not close/cancel/return other inputs after an error.
- `all-settled` observes many-valued inputs concurrently and preserves order.
- `timeout` leaves a raw many-valued channel usable after timeout.
- `timeout` does not lifecycle-return or producer-cancel a cold
  lifecycle-aware source merely because the timeout elapsed.

Also keep existing Promise-like tests passing:

- `race`
- `any`
- `all`
- `all-settled`
- `timeout`

### `time`

Required cases:

- `time` returns the original immediate value or channel-like value.
- `time` runs `print-fn` for an async expression that completes normally.
- Registering `time` inside a parent scope, then allowing that parent to
  complete, does not suppress timing output for borrowed work that completes
  later.
- Cancelling the parent scope does not cancel or suppress timing output for
  borrowed work that completes later.

## Documentation

Update README and generated docstrings where relevant:

- Promise-style combinators are single-result APIs.
- Many-valued channel inputs contribute one next take.
- Promise-style combinators do not call `areturn`, `close!`, or `cancel!` on
  inputs.
- `timeout` applies to one result, not to a source lifetime.
- `time` is observation-only instrumentation.

Run `bb gen` if exported docstrings change.

## Release Guidance

These changes are best handled before 1.0 because they tighten existing public
API semantics. Additive many-value helper APIs, namespace organization, bounded
parallelism, and async fold variants can wait until after 1.0.
