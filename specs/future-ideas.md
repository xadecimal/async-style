# Future Ideas

This file tracks unresolved design ideas that are not part of a current RFC.
Completed and superseded material is preserved in `specs/archive/`.

Related archived RFCs:

- `specs/archive/implemented/async-iteration/rfc.md`
- `specs/archive/implemented/pre-1-0-promise-combinators-and-time-observation/rfc.md`
- `specs/archive/implemented/remove-implicit-try/rfc.md`
- `specs/archive/superseded/promise-combinator-next-take/rfc.md`

## Potential Namespace Split

The completed pre-1.0 decision to retain one convenient root namespace is
archived in `specs/archive/completed-future-ideas.md`.

Reconsider a separate namespace only if the many-value API grows enough that
the root namespace becomes hard to scan.

Possible later shape:

```clojure
(require '[com.xadecimal.async-style :as a])
(require '[com.xadecimal.async-style.stream :as stream])
```

If that happens, the root namespace can still re-export common APIs for
ergonomics.

## Execution Option Keys

The prefix options position on `async`, `blocking`, and `compute` is already
reserved; that completed decision is archived in
`specs/archive/completed-future-ideas.md`.

A future RFC can define non-empty execution options such as:

```clojure
(async {:name "fetch-user"}
  ...)

(blocking {:name "read-file"}
  ...)

(compute {:name "parse-record"}
  ...)
```

Potential option categories:

- operation names for debugging, tracing, and logging,
- instrumentation metadata,
- execution/cancellation diagnostics,
- future executor or scheduling hints, if needed.

Do not use an options map as a general workflow language for `:catch`,
`:finally`, `:retry`, or `:with-resource`. Error handling, retry, and resource
management should remain explicit composable forms inside the body unless a
focused RFC makes a different case.

The option keys and their behavior deserve their own RFC before non-empty
options are accepted by `async`, `blocking`, or `compute`.

## Future Many-Value Helpers

The current generator RFC intentionally avoids adding broad stream operators.
If repeated usage shows a need, consider lifecycle-aware helpers for common
many-value workflows.

Do not add wrappers that merely duplicate Clojure transducers or core.async
channel functions. Plain `map`, `filter`, `take`, `drop`, `partition`, and
similar single-source transformations should generally remain transducers.

Possible candidates should focus on behavior that transducers/core.async do not
already cover with async-style lifecycle semantics:

- `amerge`
- `azip`
- `acombine-latest`
- `async-source`
- `from-callback`
- `adebounce`
- `athrottle`
- `asample`

These should be designed around async-style ownership and lifecycle rules, not
by changing Promise-style combinators such as `race`, `any`, `all`,
`all-settled`, or `timeout`.

## Bounded Parallelism

Bounded parallel processing is the most likely missing ergonomic feature for
the common async-iteration use cases.

Common shape:

```clojure
;; Process many inputs while limiting concurrent work to n.
(amap-par n f source)
```

or:

```clojure
(apipeline n xf-or-f source)
```

Design questions:

- Should the API preserve input order or emit results as they complete?
- Should errors fail fast, be yielded as values, or be collected?
- How should early downstream exit clean up in-flight tasks?
- Should locally spawned per-item work be owned by the consumer scope, the
  operator scope, or a per-item child scope?
- How should backpressure interact with bounded runahead?

This probably deserves its own RFC before implementation.

## Async Reductions

Current `areduce`, `atransduce`, and `ainto` use ordinary synchronous reducing
functions and transducer steps. That matches core.async and Clojure transducer
expectations: steps should be quick and should not park, block, or do heavy
work.

If async per-item reduction is needed later, prefer a separate API rather than
changing `areduce` semantics.

Possible shape:

```clojure
(afold [acc init
        x source]
  ;; body may await
  ...)
```

or a bounded variant:

```clojure
(afold-par n rf init source)
```

This should be considered distinct from transducer support.

## Pull I/O Source And Sink Adapters

The async-generator RFC demonstrates that users can adapt files, cursors, and
other pull-based resources manually. It does not define ready-made adapters for
common JVM I/O workflows.

Possible pull-based source helpers:

- `from-input-stream`
- `from-readable-byte-channel`
- `from-reader`
- `from-lines`
- `from-file`
- `from-path`

Possible sink helpers:

- `to-output-stream`
- `to-writer`
- `pipe`
- `copy`
- `drain`

Possible chunk/text helpers:

- `chunks`
- `rechunk`
- `split-lines`
- `batch`
- `partition-time-or-count`

Design goals:

- preserve backpressure with bounded memory,
- close owned resources on normal completion, error, early return, and
  cancellation,
- avoid closing borrowed streams/channels unless ownership is explicit,
- run blocking reads, writes, opens, and closes on the blocking pool,
- make byte-buffer ownership and reuse rules explicit.

Design questions:

- Should helpers open resources from paths themselves, or only adapt already
  opened resources?
- How should ownership be represented: function name, option map, wrapper type,
  or separate borrowed/owned helpers?
- Should line/text helpers use `Reader`, `InputStream` plus charset, or both?
- Should sink helpers return promise-chans, lifecycle-aware sinks, or ordinary
  functions consumed by `adoseq`?

This should be separate from push/event adapters. Pull I/O is naturally
backpressure-aware; push APIs require explicit buffering and overflow policy.

## Retry, Repeat, And Schedule Policies

Retry and repeat helpers could make async-style feel more complete for polling,
remote API calls, startup probes, and flaky I/O.

Possible public API:

- `retry`
- `repeat`
- `repeat-until`
- `repeat-while`
- `fixed-delay`
- `exponential-backoff`
- `jitter`

These should not block with `Thread/sleep`. They should use async-style parking
sleep, respect cancellation, and compose with existing async-style value/error
semantics.

Design questions:

- Is a schedule just a data map, a function, or a small protocol?
- Does `retry` receive exceptions only, `error?` values, or both?
- Should retry policies run on `async`, `blocking`, or preserve the caller's
  chosen execution context?
- How should cancellation interrupt a sleeping retry delay?
- Should there be source-level retry for `async-generator` producers, or only
  single-result retry initially?

This deserves a focused RFC if added. It is additive and does not need to block
1.0 unless retry becomes part of the initial maturity bar.

## Resource And Source Adapters

Resource-safe workflows and callback/event adaptation are not simple data
transforms. They may need dedicated async-style lifecycle APIs.

Possible public API:

- `awith-open`
- `acquire-release`
- `async-source`
- `from-callback`
- `from-resource`

Potential use cases:

- Open a file/socket/session and expose a lifecycle-aware source.
- Register a callback/listener and unregister it on `areturn`, cancellation, or
  source completion.
- Bridge push APIs into core.async-compatible async-style sources.
- Ensure release/finalizer code can run when a parent scope is cancelled.

Design questions:

- Should `acquire-release` return a single-result async value or a
  lifecycle-aware source wrapper?
- Should release receive the success/error/cancellation result?
- Should `awith-open` be a macro around `acquire-release`, or a separate
  Clojure-friendly convenience?
- How should adapters represent lossy push policies: fixed buffers only,
  dropping/sliding buffers, or explicit user-supplied core.async channels?

This also deserves its own RFC before implementation.

## `clet` Semantics

`clet` starts each binding as an async value and then rewrites later references
to await those values as needed. When used inside an async-style scope, those
binding computations are ordinary locally started child work.

The current model is acceptable, but future docs could clarify:

- `clet` is for fixed, local concurrent binding work.
- Binding computations are not detached background work.
- Unfinished locally started work is cleaned up by the surrounding ownership
  scope when that scope completes, fails, or is cancelled.

This does not currently need a behavior change.
