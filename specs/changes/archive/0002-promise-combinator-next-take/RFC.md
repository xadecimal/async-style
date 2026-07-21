# RFC: Promise Combinator Next-Take Semantics

## Status

Superseded

This change was superseded by
`../0003-pre-1-0-promise-combinators-and-time-observation/RFC.md`. The proposed
behavior was implemented through that consolidated RFC.

## Summary

Keep `race`, `any`, `all`, `all-settled`, `timeout`, and related helpers as
Promise-style single-result combinators.

When one of these APIs receives a many-valued channel, the channel contributes
one result: its next take. The combinator must not close, cancel, return, or
otherwise manage the lifecycle of the input source.

This preserves the JS Promise mental model while still making channel inputs
predictable:

```text
promise-like input      => observe its single settled result
many-valued channel     => observe one next take
lifecycle-aware source  => may start on take, but is not returned by the combinator
```

## Goals

- Preserve JS Promise-style behavior for single-result async values.
- Define many-valued channel inputs as one next-take result per input.
- Avoid lifecycle side effects in Promise-style combinators.
- Keep borrowed observation separate from ownership and cleanup.
- Ensure `race` consumes exactly the winning result when used with many-valued
  channels.
- Make `all-settled` observe inputs concurrently, matching Promise-style
  eagerness.
- Ensure `timeout` cancels only its pending wait machinery, not a
  lifecycle-aware source started for a next take.
- Document the next-take rule in public docs and docstrings.

## Non-Goals

- Do not turn these APIs into stream operators.
- Do not add implicit `areturn`, `close!`, or `cancel!` calls for many-valued
  inputs.
- Do not add stream-specific statuses such as `:done` to `all-settled`.
- Do not change nil handling for promise-like values as part of this RFC.
- Do not add dedicated stream combinators in this RFC.

## Contract

Promise-style combinators operate on one result per input.

For promise-like inputs, that result is the settled value.

For many-valued channel inputs, that result is the next take from the channel.
If taking starts a lifecycle-aware source, the source should be started in the
caller's current ownership scope, just like `anext` or `await` would start it.
The combinator does not infer that the caller is done with the source.

Lifecycle remains explicit:

```clojure
(let [x (await (timeout (anext source) 1000))]
  ...)

(await (areturn source)) ; only when the caller decides it is done
```

If the source is started inside an async-style scope, structured concurrency
still applies. Ending the owning scope cancels unfinished owned producer work and
lets generator `catch` / `finally` cleanup run. The combinator itself still does
not call `areturn`.

## API Semantics

### `race`

`race` settles with the first input result, whether that result is ok or error.

For many-valued channels, `race` should consume exactly one value total: the
winning next take. Losing inputs should not be consumed, closed, cancelled, or
returned.

This matches the Promise rule that the first settled input wins while losers are
left alone.

### `any`

`any` settles with the first ok input result. Error results are observed and
collected. If every input produces an error result, `any` settles with an
aggregate error.

For many-valued channels, each input should contribute at most one next take.
Ignoring an error result means ignoring that one input result, not continuing to
consume from the same input.

### `all`

`all` observes one result from every input and returns a vector of ok results in
input order. If any input result is an error, `all` settles with that error.

For many-valued channels, this means one next take from each input. Inputs should
be observed concurrently, and no input should be closed, cancelled, or returned
when another input errors.

### `all-settled`

`all-settled` observes one result from every input and returns a vector of those
results in input order, including error values.

For many-valued channels, this means one next take from each input. Inputs should
be observed concurrently. The result shape should remain the existing
async-style value vector, not JS `{status, value}` maps and not stream-specific
done statuses.

### `timeout`

`timeout` waits for one result from its input before the deadline.

For many-valued channels, this means one next take. On timeout, only the
combinator's pending wait machinery should be cancelled. The input source should
remain open and usable, and no lifecycle cleanup should be requested.

## Current Validation

Validation was run on 2026-06-27 with an ad hoc namespace loaded against the
current workspace.

### `race`

Current behavior can over-consume many-valued channels.

Observed result:

```clojure
{:winner :c2-a
 :remaining-c1 [:c1-b]
 :remaining-c2 [:c2-b]}
```

Both channels initially contained two values. Since `:c1-a` is missing from the
remaining values even though `:c2-a` won, current `race` can consume a losing
input's next value. This should be fixed if many-valued channels are supported
as next-take inputs.

### `any`

Current behavior matched the intended one-take-per-input rule in the checked
cases.

Observed result when one input already had an error and another had an ok value:

```clojure
{:result :c2-ok
 :remaining-c1 [:c1-after-error]
 :remaining-c2 [:c2-after-ok]}
```

Observed result when the error input completed before the ok input:

```clojure
{:result :c2-ok
 :remaining-c1 [:c1-after-error]
 :remaining-c2 [:c2-after-ok]}
```

This validates that current `any` consumed only the first result from each input
in these scenarios.

### `all`

Current behavior observes inputs concurrently and preserves input order.

Observed result:

```clojure
{:result [:first-ready :second-ready]
 :second-put-finished-before-c1? true}
```

The second unbuffered input was able to complete its put before the first input
produced a value, which validates concurrent observation.

### `all-settled`

Current behavior is sequential.

Observed result:

```clojure
{:second-put-finished-before-c1? false
 :result [:first-ready :second-ready]}
```

The second unbuffered input could not complete its put before the first input
produced a value. This should be changed for Promise-style consistency.

### `timeout` With A Raw Channel

Current behavior leaves a raw many-valued input usable after timeout.

Observed result:

```clojure
{:timeout-result :timed-out
 :source-next :later}
```

This validates that timing out cancels the pending wait rather than closing,
cancelling, or returning a raw source.

### `timeout` With A Cold Lifecycle-Aware Source

Current behavior starts and cleans up a cold lifecycle-aware source when the
timeout fires.

Observed result:

```clojure
{:timeout-result :timed-out
 :started? true
 :cleaned-within-200ms? true}
```

This is structured and leak-free, but it does not match the stricter next-take
contract in this RFC. The timeout helper currently waits through a child
`async`; cancelling that pending wait cancels owned producer work started by the
wait. If `timeout` is meant to be a pure next-take Promise combinator for
many-valued inputs, it should not make a cold lifecycle-aware source owned by
the internal timeout waiter.

### `race` With A Cold Lifecycle-Aware Source

Current behavior does not implicitly return a lifecycle-aware source after a
winning next take.

Observed result:

```clojure
{:result :first
 :cleaned-within-100ms? false
 :cleanup-after-areturn true}
```

This validates that `race` does not call `areturn` after taking one value. The
caller remains responsible for explicit early lifecycle cleanup when needed.

## Proposed Implementation Notes

- Keep `->promise-chan` preserving ordinary channels and `AsyncStyleChannel`
  values as channel-like inputs.
- Consider introducing an internal helper for observing exactly one result from
  a channel or value.
- Rework `race` so channel inputs are selected through a single pending
  selection rather than one observer per input.
- Rework `all-settled` to mirror `all`'s concurrent observation strategy while
  preserving result order and returning error values as values.
- Rework `timeout` so timing out a next take does not make a lifecycle-aware
  source owned by an internal waiter that is cancelled on timeout.
- Keep `any` under test for one-take-per-input behavior, even if no immediate
  implementation change is needed.
- Add tests for many-valued raw channels and lifecycle-aware channels proving:
  no implicit `areturn`, no source close/cancel, and no over-consumption where
  the API promises one result total.

## Documentation Notes

Public docs and docstrings should explicitly say:

- These are Promise-style combinators.
- Many-valued channels are accepted as one next-take input.
- The combinator does not close, cancel, return, or otherwise manage input
  sources.
- Call `areturn` explicitly when the caller is done with a lifecycle-aware
  source.
- Structured concurrency still cleans up owned generator producers when the
  owning scope ends.
