# RFC: Async Iteration, Transduction, and Generators

Status: Draft

## Summary

Add JS-like `for await...of` and async generator ergonomics to async-style while
using Clojure-native names and keeping the public transport as
core.async-compatible channels.

The key design choice is channel-first:

```text
async             => one result, returned as a promise-chan
async-generator   => many results, returned as a channel
await             => consume one result
anext             => consume one raw channel value, or nil when done
areturn           => tell a lifecycle-aware source the consumer is done
adoseq            => consume many results for side effects until channel close
afor              => consume many results and collect body results
areduce           => consume many results and fold into one result
atransduce        => consume many results through a transducer into one result
ainto             => consume many results into a collection
```

Async generators should not introduce a separate stream or promise type. They
should return a channel-like value that works with core.async operations, while
also carrying async-style lifecycle hooks for cleanup.

Example:

```clojure
(defn ticks []
  (async-generator
    (yield "starting")

    (await (sleep 100))
    (yield "middle")

    (await (sleep 100))
    (yield "done")

    (finally
      (println "producer cleanup"))))

(async
  (adoseq [tick (ticks)
           :while (not= tick "done")]
    (println tick)))
```

Expected behavior:

```text
starting
middle
producer cleanup
```

## Goals

- Support JS-like side-effecting consumption with `adoseq`.
- Support async comprehensions with `afor`.
- Support lifecycle-aware reductions with `areduce`, `atransduce`, and `ainto`.
- Support JS-like production with `async-generator` and `yield`.
- Support manual lifecycle-aware single-step consumption with `anext` and
  `areturn`.
- Support implicit `catch` / `finally` syntax in `async-generator`, matching
  `async`, `blocking`, and `compute`.
- Keep generated streams compatible with core.async as channels.
- Preserve cancellation follows ownership, not observation.
- Preserve every yielded value by default; no implicit dropping.
- Allow limited producer runahead with a fixed buffer size.
- Run generator `catch` / `finally` cleanup on early consumer exit, errors, or
  cancellation.
- Support Clojure `for` / `doseq` binding forms, including destructuring and
  qualifiers, in both `afor` and `adoseq`.

## Non-Goals

- Do not introduce a separate runtime or scheduler.
- Do not introduce a public stream object unrelated to channels.
- Do not wrap yielded values in `{:done? ... :value ...}` maps.
- Do not add a sentinel for yielded `nil`; nil is completion for core.async
  channels, so async generators cannot yield nil.
- Do not make promise-chans represent multiple values.
- Do not make observing a borrowed channel cancel or close that channel.
- Do not make async generators lossy. Dropping/sliding push adapters are a
  separate design.
- Do not add broad CSP process graphs in this RFC.

## Channel-First Model

An async generator returns an async-style channel. Values flow as raw non-nil
channel values. Closing the channel means the stream is done.

That means this:

```clojure
(async-generator
  (yield 1)
  (yield 2))
```

should behave like a channel that produces:

```clojure
1
2
;; then closes
```

`adoseq` over a plain channel can be just the familiar core.async loop:

```clojure
(loop []
  (when-some [x (await ch)]
    body
    (recur)))
```

Errors follow normal async-style rules. Throwable values yielded onto the channel
are thrown by lifecycle-aware consumers, because they use `await`. Future `*`
variants could use `await*` and treat Throwable values as ordinary values.

There is no JS-style `{done?, value}` wrapper and no sentinel value. A nil take
means done, exactly like core.async. As a result, `(yield nil)` is invalid.

## Async-Style Channel Wrapper

To keep compatibility and still support generator cleanup, async-style can wrap
ordinary channels in a channel-like type:

```clojure
(deftype AsyncStyleChannel [ch state]
  clojure.lang.IReference
  ;; metadata support

  clojure.core.protocols/Datafiable
  ;; delegate datafy-compatible channel introspection

  clojure.core.async.impl.protocols/Channel
  ;; delegate closed? and close!

  clojure.core.async.impl.protocols/ReadPort
  ;; delegate take!

  clojure.core.async.impl.protocols/WritePort
  ;; delegate put!

  AsyncStyleLifecycle
  ;; async-style-only start/return/finalized hooks
  )
```

The exact implementation can differ, but the contract should be:

- It is usable anywhere a core.async channel is expected.
- `chan?`, `await`, `wait`, `race`, `all`, and other async-style helpers treat
  it as a channel.
- It carries metadata, so future naming or debugging information can live on the
  channel without changing the value model.
- It implements `clojure.core.protocols/Datafiable`, delegating to the wrapped
  channel where appropriate, so it preserves the introspection behavior expected
  from a full core.async channel type.
- It may implement async-style lifecycle protocols that plain borrowed channels
  do not implement.
- Its `ReadPort/take!` implementation should ensure the generator is started
  before delegating to the wrapped channel, so ordinary core.async consumers can
  start cold generators without knowing about async-style protocols.

This keeps the public API simple:

```clojure
(def s (async-generator ...))

(ca/take! s ...)
(adoseq [x s] ...)
(afor [x s] ...)
(ainto [] s)
(race [s other])
```

Users who only know core.async still see channels. Users who use async-style get
cleanup and structured-concurrency behavior through the wrapper.

## Lifecycle Protocols

The extra async-style behavior should be expressed with small protocols on the
channel wrapper, not with wrapper values in the data stream.

Names are open, but the shape should be close to:

```clojure
(defprotocol AsyncStyleLifecycle
  (ensure-started! [ch])
  (async-return! [ch])
  (finalized-chan [ch]))
```

Semantics:

- `ensure-started!` starts a cold generator producer if it has not started yet.
- `async-return!` is an idempotent early-exit cleanup hook.
- `finalized-chan` returns a channel that closes or settles only after the
  producer body, including `finally`, has actually exited.

Plain borrowed channels do not implement these hooks. Lifecycle-aware consumers
can consume them, but early exit must not close or cancel them.

The lifecycle protocol is the reverse direction that JS async iterators model
with `return()`. It avoids putting control messages into the public value stream.
Internally, the generator may use private control channels or state to coordinate
the producer and consumer.

This is the main reason to prefer a channel wrapper over a public command/value
channel protocol. Raw yielded values stay raw, channel close stays completion,
and reverse control such as early return cannot collide with user values.

## `adoseq` And `afor`

`adoseq` is the direct Clojure spelling of JS `for await...of`: consume an async
source for side effects until the source closes.

```clojure
(adoseq [x source]
  body...)
```

`afor` is the async comprehension form: consume async sources and collect each
body result into an eager, ordered collection.

```clojure
(afor [x source
       :let [y (* x 2)]
       :when (odd? y)]
  y)
;; => promise-chan settling to a vector such as [2 6 10]
```

Both forms should return promise-chans, matching `ado`, `alet`, and `clet`.
`adoseq` settles to `nil` on normal completion. `afor` settles to a vector of
body results in iteration order. `afor` is intentionally eager; pretending to
return a lazy seq over effectful async sources would be misleading.

Binding semantics:

- The binding vector should match Clojure `for` / `doseq` as closely as
  practical.
- Binding forms can destructure.
- Qualifiers should include at least `:let`, `:when`, and `:while`.
- Multiple bindings are nested in source order, like Clojure `for` / `doseq`.
- Each binding source can be channel-like or collection-like, as described in
  the source model below.
- Qualifier expressions run inside the async-style loop. If a qualifier needs an
  async predicate, users can call `await` explicitly in the qualifier expression.
- `adoseq` and `afor` should not treat body-returned `reduced` values as a
  special early-return protocol. They should follow Clojure `doseq` / `for`:
  use `:while` to short-circuit iteration.

Examples:

```clojure
(adoseq [x (ticks)
         :let [y (* x 2)]
         :when (odd? y)
         z (more-values y)
         :while (< z 100)]
  (println z))

(afor [[k v] (entries)
       :when (await (allowed? k))]
  [k (* v 2)])
```

Consumption semantics:

- Channel-like sources are taken until close.
- Collection-like sources are iterated like ordinary Clojure collections.
- If a source has async-style lifecycle hooks, the loop calls `ensure-started!`
  before the first take.
- Each element is consumed with normal `await` semantics.
- Closed channel source or exhausted collection source means normal loop
  completion.
- If the body throws, or the current scope is cancelled, the loop calls
  `async-return!` when supported, waits for cleanup when possible, then rethrows.
- If `:while` stops iteration before a channel-like source closes, the loop
  calls `async-return!` when supported and waits for cleanup when possible.
- `adoseq` and `afor` observe their sources; they do not make already-started
  borrowed sources owned children.

Because values are raw channel values, there is no `{:done? ... :value ...}`
allocation per item.

## Source Model

Lifecycle-aware consumers should accept both async stream sources and ordinary
collection-like sources:

- Channel-like sources: core.async channels, async-generator channels, and other
  values treated as channels by async-style.
- Collection-like sources: seqs, vectors, arrays, Java iterables, and other
  ordinary finite collections where practical.

For channel-like sources, consumers take values until the channel closes. For
collection-like sources, consumers iterate as Clojure `doseq`, `for`, `reduce`,
`transduce`, or `into` would.

In both cases, each element is consumed through normal async-style value
semantics. If an element is already a value, it is used directly. If an element
is supported by `IntoPromiseChan`, it is awaited before binding, reducing, or
collecting.

Examples:

```clojure
(await (ainto [] [(async 1)
                  (future 2)
                  3]))
;; => [1 2 3]

(adoseq [x [(async "a")
            (blocking "b")]]
  (println x))
```

Cleanup depends on source kind:

- Channel-like generator sources may implement `async-return!`; early exit,
  errors, or cancellation should call it and wait for finalization when possible.
- Plain borrowed channels are observed, not closed or cancelled.
- Collection-like sources have no source cleanup; consumers simply stop
  iterating.
- Awaiting collection elements observes already-started async values and does not
  transfer ownership.

## `areduce`, `atransduce`, And `ainto`

Since async generators return channels, users can already reach for core.async
operations such as `map`, `transduce`, `into`, and `reduce`. The async-style
reason to add lifecycle-aware versions is not basic channel processing. It is
structured early termination:

```text
reduced / error / cancellation
=> call async-return! when supported
=> wait for generator finalization when possible
=> return or rethrow
```

`atransduce` should be the primitive single-source fold:

```clojure
(atransduce xf rf init source)
```

It consumes `source`, applies `xf`, and reduces with `rf`, returning a
promise-chan that settles to the final accumulator.

`areduce` is the no-transducer reduction operation:

```clojure
(areduce rf init source)
```

`areduce` should follow Clojure `reduce`: if `rf` returns `(reduced v)`, it
short-circuits, cleans up the source when needed, and the returned promise-chan
settles to `v`.

`ainto` is the async-style equivalent of `into`, and close to JavaScript
`Array.fromAsync` in spirit:

```clojure
(ainto to source)
(ainto to xf source)
```

Examples:

```clojure
(await (areduce + 0 numbers))
;; => 42

(await (atransduce (comp (filter odd?)
                         (map inc))
                   conj
                   []
                   numbers))
;; => [2 4 6]

(await (ainto #{} (filter odd?) numbers))
;; => #{1 3 5}
```

Normal completion does not need source cleanup beyond consuming the source until
it closes. Cleanup matters when the consumer stops before the source closes:

- `rf` returns a `reduced`.
- A transducer returns a `reduced`.
- `rf` or a transducer step throws.
- The consuming scope is cancelled while reducing.

In those cases, `areduce`, `atransduce`, and `ainto` should call
`async-return!` on the source when supported, await finalization when possible,
and then return or rethrow according to the operation's normal result semantics.

`reduced` semantics should match the corresponding Clojure operation:

- In `areduce`, the value inside `reduced` is the final reduced result.
- In `atransduce`, `reduced` is the normal transducer short-circuit signal. The
  returned result is the transduction result according to Clojure
  `transduce` semantics.
- In `ainto`, `reduced` is the normal transducer short-circuit signal. The
  returned result is the completed collection accumulated so far, according to
  Clojure `into` semantics.

No `reduced` value is passed upstream to the producer. `async-return!` should
remain a cleanup signal, not a data channel. If producer logic needs a value from
the consumer, that should be modeled explicitly with ordinary channels or
another API, not by overloading early-return cleanup.

These functions return promise-chans, not result values directly. They may wait,
so callers decide whether to `await` or `wait`:

```clojure
(async
  (let [sum (await (areduce + 0 numbers))]
    sum))
```

`afor` can use this machinery for collection, but it is still worth keeping as a
syntax form because it supports Clojure `for`-style destructuring, nested async
bindings, and qualifiers. `adoseq` is similarly worth keeping because it is the
direct side-effecting loop form and JS `for await...of` equivalent.

This RFC should not add `amap`, `afilter`, and similar helpers. Pure per-item
mapping and filtering should be expressed with transducers, `afor`, or generator
transformers.

## Execution Model

`async-generator`, `adoseq`, `afor`, `areduce`, `atransduce`, and `ainto` should
return promise-chans or channel-like async-style values immediately and execute
their work on the async pool, the same default execution context used by
`async`.

That means their bodies are parking orchestration code:

- `async-generator` bodies may use `await`, `yield`, `adoseq`, `afor`, and other
  parking async-style forms.
- `adoseq` and `afor` bodies and qualifiers may use `await`.
- Blocking I/O should be wrapped in `blocking`.
- CPU-heavy work should be wrapped in `compute`.

For example:

```clojure
(afor [path paths]
  (await (blocking (slurp path))))

(async-generator
  (adoseq [chunk chunks]
    (yield (await (compute (parse chunk))))))
```

The reducing functions and transducer steps passed to `areduce`, `atransduce`,
and `ainto` are different. They are ordinary synchronous reducing/transducer
code running inside the async consumer loop. They should be quick and should not
block, park, wait, perform blocking I/O, or do long CPU-heavy work. In
particular, they should not call `wait`, `wait*`, `<!!`, `>!!`,
`Thread/sleep`, or other blocking operations.

This keeps transducers honest: `atransduce` is a lifecycle-aware channel
consumer, not an async transducer system. Async or heavy per-item work should be
done with `afor`, `adoseq`, `async-generator`, `blocking`, or `compute` before
reducing/transducing cheap values.

## `async-generator`

`async-generator` creates a cold async-style channel. The body does not start
running when the channel is created. It starts when the channel is consumed by a
take operation. `adoseq` and `afor` may call `ensure-started!` explicitly, and
the wrapper's `ReadPort/take!` should also call it so ordinary core.async
consumers start the generator too.

Producer settlement must treat this channel as a multi-value source, not as a
single-result promise-like value. Returning an `async-generator` from `async`,
`blocking`, or `compute` should settle to the generator channel itself. The
generator producer still starts only when that returned channel is consumed, and
ownership belongs to that first consuming scope.

Use `async-generator` when creating a new stream boundary: adapting a resource,
transforming or expanding another source, or managing producer-side cleanup. If
the source is already a channel or collection, consumers can usually use
`adoseq`, `afor`, `areduce`, `atransduce`, or `ainto` directly.

For example, adapting a blocking cursor:

```clojure
(defn rows [cursor]
  (async-generator
    (loop []
      (when-some [row (await (blocking (jdbc/read-row cursor)))]
        (yield row)
        (recur)))
    (finally
      (.close cursor))))
```

It may accept an options map:

```clojure
(async-generator
  {:buffer-size 32}
  body...)
```

`buffer-size` controls fixed, lossless output buffering:

- Omitted: use the conservative default, `0`.
- `0`: unbuffered JS-like pull handoff; after a yielded value is taken,
  `yield` does not return and code after it does not run until the consumer asks
  for another value or returns the source.
- Positive integer: fixed buffer; the producer may run ahead by at most that
  many yielded values.

Async generators should not accept dropping or sliding buffers. `yield` should
mean the yielded value will be observed by the consumer unless the generator is
cancelled before delivery. Lossy buffering is useful for push/event sources, but
it changes the sequence contract and should live in a separate adapter design.

The generator implementation can be channel-backed:

- Create an internal output channel with the configured fixed buffer size.
- Return an `AsyncStyleChannel` wrapper around that channel.
- Start one producer task with `async` when consumption begins.
- Bind generator-local state so `(yield v)` can publish to the output channel.
- Close the output channel when the producer finishes.
- Complete a separate finalized channel only after the generator body, including
  `finally`, has actually exited.

The separate finalized channel matters because `cancel!` settles an execution's
result chan immediately. JS-like early-exit cleanup should wait for generator
cleanup to finish, not merely wait for the producer result chan to become
cancelled.

Like `async`, `blocking`, and `compute`, `async-generator` should support
implicit-try syntax. Trailing `catch` and `finally` forms belong to the
generator body:

```clojure
(async-generator
  (yield "starting")
  (await (sleep 100))
  (yield "done")
  (catch InterruptedException _
    (println "generator interrupted"))
  (finally
    (println "generator cleanup")))
```

Explicit `try` is still useful when cleanup needs access to locals bound inside
a narrower lexical scope, but simple generator cleanup should not require a
nested `try`.

## `yield`

`yield` is only valid inside `async-generator`.

```clojure
(async-generator
  (loop [i 0]
    (when (< i 10)
      (await (sleep 100))
      (yield i)
      (recur (inc i)))))
```

Semantics:

- `yield` publishes one raw value to the generator's output channel.
- `yield` must reject nil, because nil means done for core.async takes.
- It is cancellation-aware; if the generator is cancelled while parked in
  `yield`, normal `catch` / `finally` paths run.
- It should apply async-style value semantics so yielding `(async 1)`,
  `Future`, or `CompletableFuture` exposes the settled value to the consumer.
- It should provide backpressure by waiting whenever the configured fixed buffer
  is full.
- It should return `nil` on successful publication.

## Manual Consumption: `anext` And `areturn`

`anext` is the lifecycle-aware single-step counterpart to `await` over a source:

```clojure
(let [source (ticks)]
  (println (await (anext source)))
  (println (await (anext source)))
  (await (areturn source)))
```

Semantics:

- `anext` supports channel-like sources only in this RFC.
- `anext` returns a promise-chan that settles to the next raw value, or nil when
  the source is done.
- `anext` does not return a JS-style `{done?, value}` map.
- If the source is a cold async-style generator, the take starts it in the
  current consumption scope.
- If the source is a borrowed plain channel, `anext` observes it without closing
  or cancelling it.
- If the returned `anext` promise is cancelled before a borrowed plain channel
  produces a value, `anext` must not consume and drop a later value from that
  channel.
- Errors follow normal async-style behavior: `await` / `wait` throw Throwable
  results, while `await*` / `wait*` return them as values.

`areturn` is the manual cleanup counterpart:

- `areturn` requests early cleanup for lifecycle-aware async-style sources.
- It returns a promise-chan that settles to nil after cleanup has completed.
- For async generators, cleanup cancels the producer and waits for user
  `finally` / finalized cleanup to complete.
- `areturn` is idempotent.
- `areturn` on borrowed plain channels is a no-op and must not close or cancel
  them.

`cancel!` should also understand lifecycle-aware async-style sources:

- `cancel!` on an async-generator channel delegates to `async-return!`.
- It is fire-and-forget and returns according to the normal `cancel!` contract.
- It closes the output channel, unblocks paused yields, cancels the producer if
  it has started, and allows generator `catch` / `finally` to run.
- `areturn` remains the stronger manual cleanup API because it waits for the
  finalized channel.
- Raw `close!` remains only a channel operation and is not the lifecycle cleanup
  API.

## Structured Concurrency Rules

The central rule remains:

```text
Starting work creates ownership.
Awaiting work does not transfer ownership.
```

For async generators:

- Creating a cold generator channel does not start work.
- Returning a cold generator channel from `async`, `blocking`, or `compute`
  does not start work and does not make the returning scope own a producer.
- Consuming a cold generator channel with `adoseq`, `afor`, `await`, `ca/take!`,
  `ca/alts!`, or another take operation starts the producer task in the current
  scope available to that operation.
- That producer task is an owned child of the scope that starts consumption.
- Cancelling, failing, or completing that scope cancels the unfinished generator.
- Manual `cancel!`, `areturn`, and early `adoseq` / `afor` / `atransduce` /
  `areduce` / `ainto` exit call `async-return!` when supported.
- For generator channels, `async-return!` cancels the producer and waits for the
  finalized channel so generator cleanup runs.
- For borrowed channels, lifecycle-aware consumers only stop consuming; they do
  not close or cancel the borrowed source.
- If users want eager/background generation to outlive the current scope, they
  must opt in explicitly with `detach` or a future eager stream API.

This keeps the existing distinction:

```clojure
;; The generator starts inside this async scope, so this scope owns it.
(async
  (adoseq [x (ticks)]
    ...))

;; A borrowed channel is observed, not owned.
(let [ch (ca/chan)]
  (async
    (adoseq [x ch]
      ...)))
```

## Transformer Generators

Async generators should compose naturally into transformer processes, just like
JavaScript async generator functions:

```javascript
async function* double(source) {
  for await (const num of source) {
    yield num * 2;
  }
}
```

The async-style equivalent should be:

```clojure
(defn double [source]
  (async-generator
    (adoseq [num source]
      (yield (* num 2)))))
```

This works because `adoseq` can run inside an `async-generator`, and `yield`
can emit each transformed value downstream with backpressure. The transformer
remains cold: calling `(double source)` creates a channel-like value, but
producer work starts only when the returned channel is consumed.

Cleanup should cascade through nested lifecycle-aware consumers:

- If the downstream consumer exits early, `async-return!` runs for `double`.
- For a generator channel, `async-return!` cancels the `double` producer and
  waits for its finalized channel.
- Cancelling `double` interrupts its inner `adoseq`.
- The inner `adoseq` then calls `async-return!` on `source` if supported.
- If `source` is another async generator channel, its cleanup runs too.
- If `source` is a borrowed channel or collection adapter, the source itself is
  not closed or cancelled.

This gives async-style pull-based process composition without introducing a
separate CSP graph abstraction. Pipelines are ordinary functions over channels,
and ownership still follows the work that is actually started in a scope.

## Push Sources And Lossy Buffers

External push sources such as websocket events have a different problem from
async generators. They may produce values even when no consumer is currently
asking for the next item.

This RFC intentionally keeps `async-generator` lossless and pull-oriented.
Wrapping push sources can still be done with ordinary core.async channels:

```clojure
(async-generator
  (let [in (ca/chan (ca/sliding-buffer 1024))
        cleanup (register-websocket! ws #(ca/put! in %))]
    (try
      (adoseq [msg in]
        (yield msg))
      (finally
        (cleanup)))))
```

That pattern makes the lossy boundary explicit: the websocket adapter chooses a
sliding buffer for incoming events, while the generator still yields losslessly
from that adapted source to its consumer.

A future `async-source` helper may package this callback-registration pattern,
but it should remain distinct from `async-generator` because dropping/sliding
policies are source-adapter semantics, not generator semantics.

## Error And Cleanup Semantics

- Errors yielded by a generator are observed as channel values and thrown by
  lifecycle-aware consumers, because they use `await`.
- Errors from loop bodies, reducing functions, or transducer steps cause the
  consumer to run `async-return!` when supported and then rethrow.
- `reduced` from `areduce` reducing functions or transducer contexts is an
  intentional short-circuit and should also run `async-return!` when supported.
- `adoseq` and `afor` do not treat body-returned `reduced` specially; use
  `:while` for Clojure-style loop short-circuiting.
- No `reduced` value is exposed to the producer.
- Cancellation while awaiting the next item or while yielding should run normal
  user `catch` / `finally` paths.
- A generator can handle its own errors and cleanup locally, just like any
  async-style execution.
- Detached generator failures remain the responsibility of the code that
  detached them; the parent should not aggregate detached errors.

## Implementation Plan

1. Add async-style channel wrapper support in `impl.clj`.
2. Update `chan?` so async-style channel wrappers count as channels.
3. Add lifecycle protocols for start, early return, and finalized cleanup.
4. Implement `async-generator` as a cold channel-backed producer.
5. Add generator-local dynamic bindings used by `yield`.
6. Reuse or mirror the existing implicit-try machinery so `async-generator`
   supports trailing `catch` / `finally` forms.
7. Add `anext` and `areturn` for manual lifecycle-aware single-step
   consumption and cleanup.
8. Add `atransduce`, `areduce`, and `ainto` as lifecycle-aware single-source
   consumers.
9. Add `adoseq` and `afor` as macros using normal `await` semantics plus a
   cleanup path that calls `async-return!` when supported. Use the transduction
   machinery where it keeps the expansion simple.
10. Export the new public API through `build.clj` and regenerate
   `src/com/xadecimal/async_style.clj` with `bb gen`.
11. Document the async-pool execution model and the synchronous/quick
   requirement for reducing functions and transducer steps.
12. Document the distinction between generator channels, borrowed channels,
   collection-like sources, and future push-source adapters.

## Required Tests

- `async-generator` returns a value usable with core.async take operations.
- Existing async-style helpers treat an async generator result as a channel.
- `async-generator` bodies run on the async pool and can use parking
  async-style operations.
- `adoseq`, `afor`, `areduce`, `atransduce`, and `ainto` return immediately
  with promise-chans and run their consuming loops on the async pool.
- `adoseq` consumes all values from an async generator in order.
- `adoseq` exits when the generator channel closes.
- `adoseq` supports destructuring bindings.
- `adoseq` supports `:let`, `:when`, `:while`, and nested bindings like
  Clojure `doseq`.
- `adoseq` supports collection-like sources and awaits each async-style element.
- `afor` consumes all values from an async generator in order and returns a
  vector of body results.
- `afor` supports destructuring, `:let`, `:when`, `:while`, and nested bindings
  like Clojure `for`.
- `afor` supports collection-like sources and awaits each async-style element.
- `adoseq` and `afor` use `:while`, not body-returned `reduced`, for
  Clojure-style short-circuiting.
- `areduce` returns the unwrapped value when its reducing function returns
  `reduced`.
- No `reduced` value is passed to the producer via `async-return!`.
- `atransduce` consumes a source through a transducer and returns the final
  accumulator in a promise-chan.
- `atransduce` runs `async-return!` when a transducer or reducing function
  returns `reduced`.
- `atransduce` follows Clojure `transduce` result semantics for `reduced`.
- `atransduce` runs `async-return!` and rethrows when a transducer or reducing
  function throws.
- `ainto` supports `(ainto to source)` and `(ainto to xf source)`.
- `ainto` behaves like async `into`, returning the completed collection in a
  promise-chan.
- `ainto` supports collection-like sources and awaits each async-style element.
- `ainto` follows Clojure `into` result semantics for transducer
  short-circuiting.
- Early exit runs generator `finally`.
- Body exceptions run generator cleanup and are rethrown.
- Parent cancellation while waiting for the next value cancels the generator and
  runs generator cleanup.
- Parent completion/failure cancels an unfinished generator producer.
- Returning an async-generator from `async`, `blocking`, or `compute` preserves
  the source channel and does not consume its first value.
- First consumption of a returned generator starts the producer in the consuming
  scope.
- Cancelling the returning producer after it settles does not cancel an
  unconsumed returned generator.
- Cancelling the consuming parent cancels the returned generator producer and
  runs generator cleanup.
- Nested promise-like futures and async-style promise-chans still compose
  through producer settlement.
- Returning an ordinary raw channel from a producer preserves the raw channel as
  the settled value.
- `async-generator` supports implicit trailing `catch` / `finally` forms.
- Generator implicit `finally` runs on normal completion, early return, error,
  and cancellation.
- Dynamic `yield` inside loops works.
- Default `async-generator` buffering has no runahead after one `anext` or
  take; code after `yield` waits until the consumer pulls again or returns.
- `{:buffer-size 1}` allows one value of runahead.
- `anext` starts a cold generator and returns one raw value.
- `anext` returns nil when the generator is done.
- `areturn` runs generator `finally` and waits for cleanup.
- `areturn` is idempotent.
- `areturn` does not close or cancel borrowed plain channels.
- `cancel!` on a started async-generator runs generator `finally`.
- `cancel!` on an unstarted async-generator is safe and idempotent.
- `cancel!` unblocks a generator paused after an unbuffered `yield`.
- Raw `close!` is not treated as lifecycle cleanup.
- Manual `(anext source)` twice followed by `(areturn source)` works.
- Cancelling a pending `anext` over a borrowed plain channel does not consume a
  later value from that channel.
- Async generators reject yielded nil values because nil means done.
- Generator transformers can consume one channel and yield transformed values to
  another consumer.
- Early exit from a downstream consumer cascades cleanup through nested
  transformer generators.
- `async-generator` supports fixed lossless `:buffer-size`.
- `yield` provides backpressure when the configured buffer is full.
- `async-generator` rejects or does not support dropping/sliding buffers.
- Yielding an async-style value exposes the settled value to the consumer.
- A borrowed channel consumed by `adoseq`, `afor`, `atransduce`, `areduce`, or
  `ainto` is not closed or cancelled on early exit.
- Already-started async values inside collection-like sources are awaited but
  not owned by the consumer.
- `detach` or the future eager stream API allows intentionally backgrounded
  producers to outlive the current scope.
- The channel wrapper works against supported core.async versions.
- The channel wrapper supports metadata and `datafy` behavior equivalent to the
  wrapped core.async channel.

## Open Questions

- Should the public producer macro be named `async-generator`, `async-stream`,
  or should both exist with one as an alias?
- Should the public yield form be named `yield` or `yield!`?
- What exact set of collection-like sources should be supported initially:
  `Seqable`, arrays, Java `Iterable`, or all of the above?
- Which exact lifecycle protocol names best match the existing async-style API?
- How much reliance on `clojure.core.async.impl.protocols` is acceptable, and
  what compatibility tests should guard it?
- Should there be `adoseq*` and `afor*` variants that return errors as values
  instead of throwing, mirroring `await*`?
- Should there also be `atransduce*`, `areduce*`, and `ainto*`, or is the
  throwing behavior enough for reducing APIs?
- Should `ainto` use transient collection optimizations in its first
  implementation or start with simple `conj` semantics?
