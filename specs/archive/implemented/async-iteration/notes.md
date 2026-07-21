# 0001 Implementation Notes

Archive note: These notes record the async-iteration implementation process.
References to implicit trailing `catch` / `finally` syntax describe an
intermediate API that was later removed.

Working notes for implementing the async iteration/generator RFC.

## Core Decisions

- Keep the public transport channel-first. Async generators return a
  core.async-compatible channel-like value, not a new stream type.
- Yielded values are raw channel values. Channel close means completion.
- Do not use `{:done? ... :value ...}` result maps.
- Nil is completion for core.async channels, so async generators cannot yield
  nil.
- `async-generator` is cold. Producer work starts on consumption.
- Returning a cold generator from `async`, `blocking`, or `compute` preserves the
  generator channel as the settled value. It does not start the generator or make
  the returning scope own a producer.
- `anext` is the manual one-step consumer: it takes one raw value from a
  channel-like source and returns nil when done.
- `areturn` is the manual cleanup signal: it tells lifecycle-aware sources that
  the consumer is done and waits for finalization.
- `cancel!` on an async-generator channel is lifecycle-aware and delegates to
  the same cleanup path, but it is fire-and-forget. Use `areturn` when the
  caller must wait for finalization.
- Raw `close!` is only channel close and is not the lifecycle cleanup API.
- New consumer forms return promise-chans and run their loop work on the async
  pool, like `async`.
- `async-generator`, `adoseq`, and `afor` bodies may use parking async-style
  forms such as `await`.
- `areduce` reducing functions and `atransduce`/`ainto` transducer steps are
  synchronous and should stay quick.
- `async-generator` defaults to unbuffered pull behavior with `:buffer-size 0`.
  Code after `yield` remains paused after a delivered value until the consumer
  pulls again or returns the source. Positive `:buffer-size` values provide
  fixed lossless runahead; no dropping or sliding buffers in this API.
- Push/event adapters are a separate future design.

## Cancellation And Ownership Invariants

- Starting work creates ownership. Awaiting or observing work does not transfer
  ownership.
- Creating a cold generator does not start work and therefore does not create an
  owned child.
- Consuming a cold generator starts producer work in the current consumption
  scope. That producer is owned by that scope.
- Parent cancellation, failure, or completion cancels unfinished generator
  producers owned by that parent.
- Early exit from lifecycle-aware consumers calls `async-return!` only when the
  source supports async-style lifecycle hooks.
- `areturn` uses the same lifecycle cleanup path directly and is idempotent.
- `cancel!` on lifecycle-aware sources should call that same cleanup path
  without waiting for `finalized-chan`.
- Borrowed plain channels are observed only; do not close or cancel them on
  early exit.
- Collection-like sources have no source cleanup. Awaiting their elements should
  observe already-started async values without ownership transfer.
- `detach` remains the escape hatch for work intended to outlive the current
  scope.

## Cleanup Details

- A separate `finalized-chan` is needed because `cancel!` settles an execution's
  result chan immediately. Early return should wait for user `finally` to finish.
- `async-return!` should be idempotent.
- `ensure-started!` should be idempotent.
- `finalized-chan` should complete only after the generator body, including
  implicit or explicit `finally`, has exited.
- No value is sent upstream on early return. In particular, `reduced` values are
  not communicated to producers.

## Value Semantics

- Producer settlement in the existing library waits one level when a producer
  returns a promise-like single-result async value. It must not assimilate
  multi-value source channels such as `async-generator` channels or ordinary raw
  channels.
- First consumption of a returned generator starts the producer in the consuming
  scope, so ownership follows consumption rather than creation or return.
- `yield` should apply async-style value semantics before publishing, so
  yielding `(async 1)`, a `Future`, or a `CompletableFuture` exposes the settled
  value downstream.
- `anext` should not create a short-lived owner scope around generator startup;
  otherwise the one-step helper would cancel the generator as soon as it returns
  its first value. It should start/take in the caller's current cancellation
  context and return a plain promise-chan for the step result.
- Do not reintroduce recursive await/wait flattening as part of this feature.

## Consumer Semantics

- `adoseq` is the JS `for await...of` equivalent and settles to `nil`.
- `afor` is eager and settles to a vector of body results in iteration order.
- `adoseq` and `afor` should support Clojure `for`/`doseq` binding features:
  destructuring, nested bindings, `:let`, `:when`, and `:while`.
- `adoseq` and `afor` do not treat body-returned `reduced` specially; use
  `:while` for short-circuiting.
- `areduce` follows Clojure `reduce`: `(reduced v)` settles to `v`.
- `atransduce` follows Clojure `transduce`.
- `ainto` follows Clojure `into`, including transducer short-circuit behavior.

## Implementation Hazards

- The channel wrapper must work with core.async internals across supported
  versions. Add compatibility tests before leaning too heavily on impl details.
- Do not use `satisfies?` against core.async `ReadPort` to decide whether a
  value is a channel. Core.async has broad protocol machinery there. Use the
  concrete core.async channel class plus async-style's own marker protocol.
- Wrapper `ReadPort/take!` should call `ensure-started!` so ordinary core.async
  consumers start cold generators.
- Generator bodies must be inlined under `async` by the `async-generator` macro.
  Passing the body through a runtime function causes parking forms inside
  `yield` to compile outside core.async's go state machine.
- If consumption starts outside an async-style scope, the producer has no parent
  scope. That should be valid but not structured under a parent.
- Avoid closing borrowed channels during cleanup paths.
- Avoid making watcher/helper tasks own borrowed inputs.
- Be careful with dynamic bindings in macro expansions so `yield` sees the
  correct generator state and cancellation channel.
- Keep generated public namespace changes mechanical by editing `impl.clj` /
  `protocols.clj`, then running `bb gen`.

## Suggested Implementation Order

1. Build and test the channel wrapper plus lifecycle protocols.
2. Implement cold `async-generator` without implicit-try first.
3. Add `yield` and basic consumption with core.async take operations.
4. Add implicit-try and finalization semantics.
5. Add lifecycle-aware source helpers.
6. Implement `atransduce`, then derive `areduce` and `ainto`.
7. Implement `adoseq`, then `afor`.
8. Add transformer and structured-concurrency edge-case tests.
9. Export, regenerate, document, and run the full suite.
