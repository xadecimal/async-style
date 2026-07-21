# 0001 Implementation Tasks

Status: Completed.

Archive note: Checked implicit-try tasks record an intermediate implementation
that was subsequently removed by the implicit-try removal RFC.

Task breakdown for implementing
`RFC.md`.

## 0. Lock Initial API Decisions

- [x] Decide public names: `async-generator` and `yield`.
- [x] Decide initial source support: core.async channels, async-style channel wrappers, `Seqable`, arrays, and Java `Iterable`.
- [x] Decide lifecycle protocol names and whether they live in `impl.clj` or `protocols.clj`.
- [x] Decide initial `:buffer-size` default: `0`.
- [x] Confirm no initial `adoseq*`, `afor*`, `areduce*`, `atransduce*`, or `ainto*` variants.

## 1. Channel Wrapper Foundation

- [x] Add an async-style channel wrapper around a core.async channel.
- [x] Implement core.async channel protocols required by supported core.async versions:
  `Channel`, `ReadPort`, and `WritePort`.
- [x] Implement metadata support with `clojure.lang.IReference`.
- [x] Implement `clojure.core.protocols/Datafiable`, delegating to the wrapped channel where appropriate.
- [x] Add lifecycle protocol support for start, early return, and finalization.
- [x] Update `chan?` and any channel predicates/coercion paths so wrappers are treated as channels.
- [x] Add focused compatibility tests for core.async 1.7 and the default core.async version.

## 2. Lifecycle Machinery

- [x] Implement idempotent `ensure-started!` for cold producers.
- [x] Implement idempotent `async-return!` for early consumer exit.
- [x] Implement `finalized-chan`, distinct from the producer result chan, so cleanup waits for user `finally`.
- [x] Add helper functions for lifecycle-aware source cleanup:
  start when supported, return when supported, and wait for finalization when supported.
- [x] Ensure borrowed plain channels have no cleanup side effect.
- [x] Ensure collection-like sources have no cleanup side effect.

## 3. `async-generator`

- [x] Implement macro option parsing for:
  `(async-generator body...)` and `(async-generator {:buffer-size n} body...)`.
- [x] Create a fixed, lossless output channel from `:buffer-size`.
- [x] Make generators cold: creation returns immediately and body starts on first consumption.
- [x] Ensure returning a generator from `async`, `blocking`, or `compute` preserves the source channel instead of consuming its first yielded value.
- [x] Start the producer with `async` so it is owned by the consumption scope.
- [x] Bind generator-local state used by `yield`.
- [x] Close the output channel on normal producer completion.
- [x] Cancel/close correctly on parent cancellation, early return, and producer error.
- [x] Reuse/mirror `implicit-try` so trailing `catch` and `finally` forms work.
- [x] Ensure user `catch` and `finally` run on normal completion, error, early return, and cancellation.

## 4. `yield`

- [x] Implement `yield` as valid only inside `async-generator`.
- [x] Apply async-style value semantics before publishing: yielded futures, completable futures, and promise-chans expose their settled value.
- [x] Publish raw values to the output channel; do not wrap values in `{:done? ...}` maps.
- [x] Reject yielded nil values because nil means done for core.async channels.
- [x] Make `yield` cancellation-aware while parked on a full buffer or unbuffered handoff.
- [x] For default unbuffered generators, keep code after `yield` paused until the consumer pulls again or returns the source.
- [x] Return `nil` on successful yield.
- [x] Add tests for invalid usage outside a generator.

## 4a. Manual Lifecycle Consumption

- [x] Add public `anext`.
- [x] Make `anext` support channel-like sources only.
- [x] Make `anext` start cold async-style generators in the current consumption scope.
- [x] Make `anext` settle to one raw value or nil when the source is done.
- [x] Keep `anext` observation-only for borrowed plain channels.
- [x] Ensure cancelling a pending `anext` does not consume and drop a later value from a borrowed plain channel.
- [x] Add public `areturn`.
- [x] Make `areturn` request async-style source cleanup and wait for finalization.
- [x] Make `areturn` idempotent.
- [x] Make `areturn` a no-op for borrowed plain channels.
- [x] Make `cancel!` on async-generator channels request lifecycle cleanup through `async-return!`.
- [x] Keep `cancel!` fire-and-forget; use `areturn` when callers need to wait for finalization.
- [x] Keep raw `close!` separate from lifecycle cleanup.

## 5. Source Model Helpers

- [x] Add a source abstraction/helper layer for channel-like and collection-like inputs.
- [x] Channel-like sources consume until close.
- [x] Collection-like sources iterate like ordinary Clojure collections.
- [x] Await each element through async-style semantics before binding, reducing, or collecting.
- [x] Preserve observation semantics: awaiting collection elements does not make already-started work owned by the consumer.
- [x] Add tests for arrays and Java iterables.

## 6. `atransduce`, `areduce`, And `ainto`

- [x] Implement `atransduce` as the primitive lifecycle-aware single-source fold.
- [x] Ensure `atransduce` returns a promise-chan and runs on the async pool.
- [x] Implement `areduce` on top of the same source/lifecycle machinery.
- [x] Implement `ainto` arities:
  `(ainto to source)` and `(ainto to xf source)`.
- [x] Make reducing functions and transducer steps synchronous and quick; document that they must not block, park, or await.
- [x] Match Clojure `reduce` semantics for `areduce` and `reduced`.
- [x] Match Clojure `transduce` and `into` semantics for transducer short-circuiting.
- [x] On reduced/error/cancellation, call `async-return!` when supported and wait for finalization when possible.
- [x] Do not pass reduced values upstream to producers.
- [x] Add tests for thrown errors in `atransduce`.
- [x] Reuse `areturn` in cleanup paths where it stays simple and does not bloat macro expansion.

## 7. `adoseq` And `afor`

- [x] Implement `adoseq` as async `doseq`, returning a promise-chan that settles to `nil`.
- [x] Implement `afor` as async eager `for`, returning a promise-chan that settles to a vector.
- [x] Support destructuring bindings.
- [x] Support nested bindings in source order.
- [x] Support `:let`, `:when`, and `:while`.
- [x] Allow explicit `await` in bodies and qualifier expressions.
- [x] Treat body-returned `reduced` as an ordinary value; use `:while` for loop short-circuiting.
- [x] On `:while`, body error, or cancellation, call lifecycle cleanup when supported.
- [x] Add more tests matching Clojure `doseq`/`for` behavior for nested binding forms and ordering.

## 8. Structured Concurrency Semantics

- [x] Verify a generator consumed inside `async` is owned by that scope.
- [x] Verify parent cancellation cancels unfinished generator producers.
- [x] Verify parent normal completion cancels unfinished generator producers.
- [x] Verify returning a generator does not start its producer or make it owned by the returning scope.
- [x] Verify first consumption of a returned generator starts the producer in the consuming scope.
- [x] Verify cancelling the returning producer after settlement does not cancel an unconsumed returned generator.
- [x] Verify early downstream exit runs generator cleanup before the consumer settles.
- [x] Verify transformer cleanup cascades through nested generator sources.
- [x] Verify borrowed channels are observed only and are not closed or cancelled.
- [x] Verify `detach` can be used for intentional background/eager generation.
- [x] Verify `race`, `any`, `all`, `all-settled`, and `timeout` keep observation semantics with generator channels.
- [x] Verify `cancel!` on a started async-generator runs `finally`.
- [x] Verify `cancel!` on an unstarted async-generator is safe and idempotent.
- [x] Verify `cancel!` unblocks a generator paused after an unbuffered `yield`.
- [x] Verify raw `close!` is not treated as lifecycle cleanup.
- [x] Verify manual `(anext source)` twice followed by `(areturn source)` works.
- [x] Verify default `:buffer-size 0` has no runahead after one `anext` / take.
- [x] Verify `{:buffer-size 1}` allows one value of runahead.
- [x] Verify nested promise-like futures and async-style promise-chans still compose through producer settlement.
- [x] Verify returning an ordinary raw channel preserves it as a value.

## 9. Public API And Generated Namespace

- [x] Add exported vars to `build.clj`.
- [x] Regenerate `src/com/xadecimal/async_style.clj` with `bb gen`.
- [x] Ensure generated docstrings mention execution context, lifecycle cleanup, one-level value settlement, and borrowed observation.
- [x] Keep implementation details out of the generated public namespace except through documented APIs.

## 10. Documentation

- [x] Update `README.md` with async generator examples.
- [x] Document `adoseq`, `afor`, `areduce`, `atransduce`, and `ainto`.
- [x] Document structured concurrency rules for generator consumption and borrowed sources.
- [x] Document fixed lossless `:buffer-size` and why dropping/sliding buffers are not part of `async-generator`.
- [x] Document that reducing functions and transducer steps are synchronous/quick.
- [x] Update `CHANGELOG.md` under Unreleased.
- [x] Update `AGENTS.md` if new implementation invariants should guide future contributors.

## 11. Test And Release Checks

- [x] Add targeted tests near related existing test sections in `test/com/xadecimal/async_style_test.clj`.
- [x] Use `bb test-vars` while developing focused test groups.
- [x] Run `bb gen` after public API/docstring changes.
- [x] Run `bb test`.
- [x] Run `bb lint`.
- [x] Review generated diffs, especially `src/com/xadecimal/async_style.clj`.
- [x] Confirm no unrelated workspace files, such as `.DS_Store`, are staged.
