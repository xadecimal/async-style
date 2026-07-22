# Async Iteration

## Generator Model

`async-generator` returns a cold, `core.async`-compatible channel-like source.
Creation and return do not start producer work. The first take starts the
producer in the current consumption scope, making it an owned child of that
scope when a scope exists.

`yield` publishes a settled, raw, non-nil value. A `Throwable` produced by the
generator is published as the final source result before close and is rethrown
by await-aware consumers. Channel close, observed as a nil take, means the
source is done; generators therefore cannot yield nil and do not use
JavaScript-style `{done?, value}` wrappers.

The default `:buffer-size` is `0`. This implements unbuffered pull behavior:
after a value is delivered, code following `yield` waits until another pull or
lifecycle return. A positive buffer size permits bounded lossless runahead.
Dropping and sliding behavior is not supported by `async-generator`.

## Manual Lifecycle

`anext` supports channel-like sources, starts a cold generator in the caller's
current scope, and returns one raw value or nil when done. It observes a plain
borrowed channel without closing or cancelling it.

`areturn` requests idempotent early cleanup and waits for generator
finalization, including user `finally` forms. It is a no-op for a borrowed
plain channel. `cancel!` requests the same generator lifecycle cleanup without
waiting.

Cleanup closes generator output, unblocks a paused yield, cancels a started
producer, and permits the producer's `catch` and `finally` forms to run.

## Lifecycle-Aware Consumers

The library provides:

- `adoseq` for side-effecting iteration, settling to nil.
- `afor` for eager iteration, settling to a vector of body results.
- `areduce` for reduction, including `(reduced value)` short-circuiting.
- `atransduce` for transduction.
- `ainto` for collection, optionally through a transducer.

`adoseq` and `afor` support destructuring, nested bindings, `:let`, `:when`,
and `:while`. Iteration sources may be channel-like or collection-like,
including seqables, arrays, and `Iterable` values. Collection elements are
awaited using async-style value semantics.

On early exit, reduced completion, failure, or cancellation, lifecycle-aware
consumers request generator cleanup and wait for finalization. Plain borrowed
channels are observation-only and are not closed or cancelled.

Reducing functions and transducer steps run in the async execution context
and must remain quick and synchronous. Blocking, parking, I/O, and heavy
computation belong in an execution form or upstream async generator.
