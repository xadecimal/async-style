# async-style

> **async / await for Clojure.**

A Clojure library that brings JavaScript's intuitive async/await and Promise APIs to Clojure, along with utilities for cancellation, timeouts, racing, and more. It brings familiar async/await style of programming to Clojure.

---

## 📖 Table of Contents
- [Features](#-features)
- [Quick Start](#-quick-start)
- [Installation](#-installation)
- [Why async-style?](#why-async-style)
- [Core Concepts](#core-concepts)
  - [Execution contexts](#execution-contexts-async-blocking-compute)
  - [Awaiting](#awaiting-await-wait-await-wait)
  - [Promise coercion](#promise-coercion)
  - [Producer settlement](#producer-settlement)
  - [Async iteration](#async-iteration)
  - [Errors](#errors)
  - [Cancellation](#cancellation)
- [API Overview](#api-overview)
- [Helpers: ado, alet, clet, time](#helpers-ado-alet-clet-time)
- [AI Disclosure](#ai-disclosure)
- [Contributing](#-contributing)
- [License](#-license)
- [Support](#️-support)

---

## 🚀 Features

- **Async/Await Syntax:** Familiar JavaScript-style asynchronous programming in Clojure.
- **Rich Error Handling:** Built-in mechanisms for handling asynchronous exceptions gracefully.
- **Flexible Execution Pools:** Optimized for I/O-bound, compute-heavy, and lightweight tasks.
- **Cancellation Support:** Easily cancel asynchronous operations, ensuring efficient resource management.
- **Comprehensive Utilities:** Timeout, sleep, chaining tasks, racing tasks, and more.
- **Async Iteration:** JS-like async generators plus `adoseq`, `afor`, `areduce`, `atransduce`, and `ainto`.

---

## 🔥 Quick Start

```clojure
(ns example.core
  (:require [com.xadecimal.async-style :as a
             :refer [async blocking compute await wait cancel! cancelled?]]))

;; Simple async operation
(async
  (println "Sum:" (await (async (+ 1 2 3)))))

;; Blocking I/O, asynchronous waiting
(async
  (println (await (blocking
                    (Thread/sleep 500)
                    "Blocking completed!"))))

;; Blocking I/O, synchronous waiting
(println "Sync blocking result:"
         (wait (blocking
                 (Thread/sleep 500)
                 "Blocking sync done")))

;; Heavy compute, asynchronous waiting
(async
  (println "Factorial:"
           (await (compute (reduce * (range 1 20))))))

;; Heavy compute, synchronous waiting
(println "Sync compute factorial:"
         (wait (compute (reduce * (range 1 20)))))

;; Handle an error
(async
  (try
    (println "Divide:" (await (async (/ 1 0))))
    (catch ArithmeticException _
      (println "Can't divide by zero!"))))

;; Cancel an operation
(let [task (blocking (Thread/sleep 5000)
                     (when-not (cancelled?)
                       (println "This won't print")))]
  (Thread/sleep 1000)
  (cancel! task "Cancelled!")
  (println "Task result:" (wait task)))
```

---

## 📦 Installation

### Leiningen

```clojure
[com.xadecimal/async-style "0.4.0"]
```

### Clojure CLI (deps.edn)

```clojure
{:deps {com.xadecimal/async-style {:mvn/version "0.4.0"}}}
```

---

## Why async-style?

Core.async and the CSP style is powerful, but its `go` blocks and channels can feel low‑level compared to JS Promises and async/await. **async-style** provides:

- **Familiar ergonomics**: `async`/`await` like in JavaScript, Python, C#, etc.
- **Expanded ergonomics**: `blocking`/`wait` for I/O and `compute`/`wait` for heavy compute.
- **Workload-specific execution**: distinct execution forms for async control flow (`async`), blocking I/O (`blocking`), and CPU-bound work (`compute`).
- **Built on core.async**: core.async under the hood, can be used alongside it, promises are core.async's `promise-chan`.
- **First-class error handling**: unlike core.async, errors are bubbled up and properly handled.
- **First‑class cancellation**: cancellation follows ownership and propagates through promise‑chans.
- **Interop coercion**: `Future`, `CompletableFuture`, and `IBlockingDeref` can be observed through `->promise-chan`.
- **Rich composition**: `then`, `chain`, `race`, `all`, `any`, `all-settled`.
- **Convenient macros**: `ado`, `alet`, `clet` for sequential, ordered, or concurrent binding.
- **Railway programming**: supports railway programming with `async`/`await*`, if preferred.

---

## Core Concepts

### Execution contexts: `async`, `blocking`, `compute`

async-style follows the best practice outlined here: [Best practice for async/blocking/compute pools](https://gist.github.com/djspiewak/46b543800958cf61af6efa8e072bfd5c)

It offers `async`/`blocking`/`compute` so callers describe the kind of work
being performed. The library then selects the matching execution context.

Each execution macro reserves a leading literal map for future options when at
least one body form follows it. The options map must currently be empty;
non-empty option maps fail during macro expansion until their keys have defined
semantics. A sole map remains an ordinary body value, so `(async {})` returns
`{}`. To evaluate a map first in a multi-form body, wrap it explicitly.

| Macro | Execution model | Use for… |
|---|---|---|
| `async` | A `core.async` IOC `go` block on its platform-thread dispatch executor | Async control flow, `await`, polling, and short non-blocking work |
| `blocking` | `core.async/io-thread`; one virtual thread per task with current `core.async` on Java 21+, otherwise a cached platform-thread executor | Blocking I/O, sleeps, and blocking waits |
| `compute` | Clojure's fixed Agent pool (available processors + 2 platform threads) | CPU-intensive work; do not block, park, or wait |

`async` does not switch to virtual threads when they are available. On the
supported `core.async` 1.7 compatibility path, it uses the traditional fixed
go-dispatch pool (eight platform threads by default), while `blocking` falls
back to `core.async/thread` on a cached platform-thread executor. In
`core.async` 1.8, both go dispatch and `io-thread` use cached platform-thread
execution. Exact executors may also be customized through core.async, but the
workload guidance above remains the same.

Cancellation interrupts a running `blocking` or `compute` worker, so
interruptible operations normally stop promptly. Cancellation remains
cooperative: code that ignores interruption can continue running. Likewise, a
top-level or detached blocking task is an explicit thread-lifetime task and is
not stopped merely because its result channel becomes unreachable.

### Awaiting: `await`, `wait`, `await*`, `wait*`

- **`await`** (inside `async`): parks current async execution until a promise‑chan or supported async value completes; re‑throws errors.
- **`wait`** (outside `async`): blocks calling thread for a result or throws error.
- **`await*` / `wait*`**: return exceptions as values (no throw).

`await` is like JavaScript's await, but because `async` remains a core.async
`go` block, it cannot park across function boundaries. Be careful with
higher-order functions such as `map` and `run!`: their function bodies cannot
contain `await`. Virtual-thread availability does not remove this restriction.

`wait` is the counter-part to `await`, it is like `await` but synchronous. It doesn't color your functions, but will block your thread.

`await*` / `wait*` are variants that return the exception as a value, instead of throwing.

`await` and `wait` accept core.async channels, async-style promise-chans, plain values, and values supported by `IntoPromiseChan`.
They explicitly take from channel-like inputs. If a producer settled to a source channel as its value, `await` / `wait` of that producer returns the source channel itself.

### Promise coercion

`->promise-chan` converts supported asynchronous values into promise-chans:

- core.async channels and async-style promise-chans pass through unchanged.
- `Future`, `CompletableFuture`, and other `IBlockingDeref` values are observed from a detached `blocking` task.
- Cancelling the returned promise-chan attempts to cancel the underlying `Future` / `CompletableFuture` when that is supported.
- Plain values pass through unchanged.

Most async-style APIs call `->promise-chan` for their inputs, so helpers such as `await`, `wait`, `timeout`, `race`, `any`, `all`, and `all-settled` can observe futures as well as promise-chans.

### Producer settlement

When `async`, `blocking`, or `compute` returns a promise-like single-result async value, async-style waits one level before settling the producer's result channel.
This keeps the producer's lifetime aligned with returned promise-like values:

```clojure
(wait (async (async (async 1)))) ; => 1
```

Nested async-style producers still compose because each producer waits one promise-like level before it settles. Multi-value source channels are different: returning an `async-generator` or ordinary raw channel settles to the channel itself. The source is not consumed by settlement, and a cold generator's producer starts only when that returned channel is later consumed.

### Async iteration

`async-generator` is the many-value counterpart to `async`. It returns a cold core.async-compatible channel-like value. The generator body starts when the returned channel is consumed, not when the generator is created.

```clojure
(defn ticks []
  (async-generator
    (try
      (yield "starting")
      (await (sleep 100))
      (yield "middle")
      (await (sleep 100))
      (yield "done")
      (finally
        (println "cleanup")))))

(async
  (adoseq [tick (ticks)
           :while (not= tick "done")]
    (println tick)))
```

Values yielded by `yield` are raw channel values. Channel close means the generator is done. `yield` applies async-style value semantics before publishing, so yielding `(async 1)`, a `Future`, or a `CompletableFuture` exposes the settled value to consumers. Because async-style stays channel-first, generators cannot yield `nil`; a `nil` take means done.

If the generator body fails, its `Throwable` is delivered through the output channel before that channel closes. Await-aware consumers rethrow it using normal async-style error semantics, and generator `finally` cleanup still runs. Lifecycle cancellation and `areturn` cleanup do not publish their internal interruption signals as source values.

The async iteration consumers are:

- `anext`: manually take one raw value; settles to `nil` when done.
- `areturn`: manually request early cleanup and wait for generator `finally`.
- `adoseq`: async `doseq` for side effects; settles to `nil`.
- `afor`: async eager `for`; settles to a vector of body results.
- `areduce`: async reduce; `(reduced v)` short-circuits and settles to `v`.
- `atransduce`: async transduce with lifecycle-aware early cleanup.
- `ainto`: async `into`, optionally with a transducer.

`adoseq` and `afor` support destructuring, nested bindings, `:let`, `:when`, and `:while`. Async iteration consumers can consume channel-like sources or collection-like sources, including Clojure seqables, JVM arrays, and `java.lang.Iterable` values. Collection elements are awaited, so this works:

```clojure
(wait (afor [x [(async 1) (future 2) 3]
             :let [y (* x 2)]]
        y))
;; => [2 4 6]
```

Manual stepping is available when a caller wants explicit lifecycle control:

```clojure
(let [source (ticks)]
  (println (wait (anext source)))
  (println (wait (anext source)))
  (wait (areturn source))) ; runs generator finally
```

`anext` only supports channel-like sources for now. It starts a cold generator if needed, observes borrowed plain channels without closing or cancelling them, and returns a promise-chan that settles to the next raw value or `nil` when the source is done. It does not return a JS-style `{done?, value}` map. `areturn` is a no-op for borrowed plain channels.

For async generators, `(cancel! source)` is lifecycle-aware fire-and-forget cancellation: it closes the output channel, unblocks paused yields, cancels the producer if it started, and lets generator `catch` / `finally` run. Use `areturn` when you need to wait until `finally` / finalization has completed. `close!` on a generator is only the lower-level raw channel close operation, not the lifecycle cleanup API.

Async generators use fixed, lossless buffering:

```clojure
(async-generator
  {:buffer-size 32}
  (yield :event))
```

The default buffer size is `0`, which means an unbuffered, JS-like pull handoff: after a consumer takes a yielded value, `yield` does not return and code after it does not run until the consumer asks for another value or calls `areturn`. Use a positive `:buffer-size` when you want bounded runahead. Dropping and sliding buffers are intentionally not part of `async-generator`, because a generator models a lossless sequence. Use an explicit core.async channel adapter for push/event sources that need lossy buffering.

Async iteration follows the same ownership rule as the rest of async-style. Creating or returning a cold generator does not start work. Consuming it starts its producer in the current scope, making that producer an owned child of the consuming scope. Early `areturn`, `adoseq`, `afor`, `areduce`, `atransduce`, or `ainto` exit calls generator cleanup and waits for `finally` to run. Borrowed plain channels are only observed; they are not closed or cancelled.

Reducing functions and transducer steps passed to `areduce`, `atransduce`, and `ainto` run inside the async execution context. Keep them quick and synchronous. Do not block, park, `await`, `wait`, perform I/O, or do heavy compute there; wrap that work in `blocking`, `compute`, `adoseq`, `afor`, or an `async-generator` first.

### Errors

#### Explicit `try` / `catch` / `finally`

Use ordinary Clojure `try` forms inside `async`, `blocking`, `compute`, and
`async-generator` when handling errors or running cleanup. `await` and `wait`
each accept exactly one value; wrap the call in `try` when its errors need to be
handled locally.

```clojure
(async
  (try
    (/ 1 0)
    (catch ArithmeticException e
      (println "oops:" e))
    (finally
      (println "done"))))
```

Explicit `try` keeps normal lexical scope, so cleanup can access resources
bound outside or around it.

#### Error Handling Combinators

async-style gives you first‑class combinators to catch, recover, inspect or always run cleanup on errors:

- **`catch`**
  Intercept errors of a given type or predicate, return a fallback value:
  ```clojure
  ;; recover ArithmeticException to 0
  (-> (async (/ 1 0))
      (catch ArithmeticException (fn [_] 0)))
  ```

- **`finally`**
  Always run a side‑effect (cleanup, logging) on both success or error, then re‑deliver the original result or exception:
  ```clojure
  (-> (async (/ 1 0))
      (finally (fn [v] (println "Completed with" v))))
  ```

- **`handle`**
  Always invoke a single handler on the outcome (error or value) and deliver its return:
  ```clojure
  ;; log & wrap both success and error
  (-> (async (/ 1 0))
      (handle (fn [v] (str "Result:" v))))
  ```

- **`then`**
  Attach a success callback that is skipped if an error occurred (short‑circuits on error):
  ```clojure
  (-> (async 5)
      (then #(+ % 3))
      (then println))
  ```

- **`chain`**
  Shorthand for threading multiple `then` calls, with the same short‑circuit behavior:
  ```clojure
  (-> (async 5)
      (chain inc #(* 2 %) dec)
      (handle println))
  ```

#### Railway style with `await*` / `wait*`

By default `await`/`wait` will **throw** any exception taken from a chan. If you prefer **railway programming** (errors as values), use:

- **`await*`** (parking, non‑throwing)
- **`wait*`**  (blocking, non‑throwing)

They return either the successful value *or* the `Throwable`. Functions `error?` and `ok?` can than be used to branch on their result:

```clojure
(async
  (let [result (await* (async (/ 1 0)))]
    (if (error? result)
      (println "Handled error:" (ex-message result))
      (println "Success:" result))))
```

Errors are always modeled this way in async-style, unlike in JS which wraps errors in a map, in async-style they are either an error? or an ok? result:

```clojure
(async
  (let [vals (await* (all-settled [(async 1) (async (/ 1 0)) (async 3)]))]
    (println
      (map (fn [v] (if (error? v) :err v)) vals))))
;; ⇒ (1 :err 3)
```

### Cancellation

Cancellation in **async-style** is **cooperative**—you signal it with `cancel!`, but your code must check for it to actually stop.

Cancellation follows ownership, not observation:

- Each execution's result promise-chan is also its cancellation token while that execution body runs.
- Starting work with `async`, `blocking`, or `compute` inside a running execution creates a direct owned child, unless the start happens inside `detach`.
- Cancelling a parent cancels its direct children; each child then cancels its own direct children, so cancellation propagates transitively through the tree.
- When a parent completes normally or fails, unfinished owned direct children are cancelled.
- Awaiting, racing, timing out, or aggregating already-started work only observes it. Combinators such as `race`, `any`, `all`, `all-settled`, and `timeout` do not take ownership of borrowed input promises/channels or call `areturn`, `close!`, or `cancel!` on them.
- `race` and `any` do not cancel losers because they lost. Locally started losers are only cancelled if their owning parent scope ends while they are still unfinished.
- Use `detach` when intentionally starting background work that should outlive the current parent scope.

#### Structured concurrency semantics

Each `async`, `blocking`, and `compute` execution creates a cancellation scope. While the body runs, that execution's own result promise-chan is bound as the current cancellation token. Work started inside that scope becomes a direct owned child unless it is started inside `detach`.

Owned children are cancelled when:

- the parent is cancelled,
- the parent fails,
- the parent completes while the child is still unfinished.

Only direct children are registered on a parent. Transitive cancellation happens recursively: the parent cancels its direct children, each child cancels its direct children, and so on.

Observation does not create ownership. Passing an already-started promise, channel, `Future`, or `CompletableFuture` to `await`, `wait`, `timeout`, `race`, `any`, `all`, or `all-settled` observes it without making it a child of the current scope. Internal watcher logic, when used, belongs to the current operation but does not transfer ownership of the watched input.

Producer settlement and scope lifetime are tied together. If a producer returns a promise-like single-result async value, the producer waits one level for that value before settling its own result channel. Cleanup of unfinished owned children happens only after the producer's result channel is actually settled. Nested async-style producers compose because each producer performs this one-level wait. Multi-value source channels, including async generators and ordinary raw channels, are preserved as values; first consumption owns any cold generator producer.

Borrowed work keeps its original owner:

```clojure
(let [p1 (async slow)
      p2 (async fast)]
  (async
    (race [p1 p2]))) ; observes p1/p2; does not own or cancel them
```

Locally started work is owned by the surrounding scope:

```clojure
(async
  (race [(async slow)
         (async fast)]))
;; race observes both; when the parent scope settles, any unfinished owned child is cancelled.
```

The same distinction applies when a combinator is returned from an `async` body:

```clojure
;; Borrowed inputs: the inner async only observes p1/p2.
(let [p1 (async slow)
      p2 (async fast)]
  (async
    (race [p1 p2])))
;; If the inner async is cancelled, only its waiting/race logic is cancelled.
;; p1 and p2 keep running under their original owner.

;; Locally started inputs: slow and fast are owned by the outer async.
(async
  (race [(async slow)
         (async fast)]))
;; race itself does not cancel the loser. But after race settles the parent
;; async settles too, and parent cleanup cancels any unfinished owned child.

;; Explicit background work: detach removes the ownership edge.
(async
  (detach
    (async slow-background-work))
  :parent-done)
;; The detached child may continue after the parent completes or is cancelled.
```

Returning a combinator does not make the combinator own borrowed inputs. It only keeps the parent producer alive until the returned combinator's promise-chan settles. After that, normal parent cleanup applies to unfinished children that were actually started inside the parent scope.

- **`cancel!`**
  ```clojure
  (cancel! ch)              ; cancel with CancellationException
  (cancel! ch custom-val)   ; cancel with custom-val (must not be nil)
  ```
  Marks the promise‑chan `ch` as cancelled. If the block hasn’t started, it immediately fulfills; otherwise it waits for you to check. On lifecycle-aware async-style sources such as async generators, `cancel!` requests source cleanup and returns immediately; use `areturn` to wait for cleanup.

- **`cancelled?`**
  ```clojure
  (when-not (cancelled?) …)
  ```
  Returns `true` inside `async`/`blocking`/`compute` if `cancel!` was called. Does **not** throw.

- **`check-cancelled!`**
  ```clojure
  (check-cancelled!)  ; throws InterruptedException if cancelled
  ```
  Throws immediately when cancelled, so you can use it at safe points to short‑circuit heavy loops or I/O.

- **Handling cancellation downstream**
  - `await` / `wait` will re‑throw a `CancellationException` (or return your custom val).

- **`detach`**
  ```clojure
  (async
    (detach
      (async background-work)))
  ```
  Runs the body outside the current cancellation context. Work started inside `detach` is not owned by the current parent and can outlive parent cancellation, failure, or normal completion. `detach` can also be used around `await` / `wait` when you intentionally do not want to read the current parent's cancellation token.

```clojure
(def work
  (async
    (loop [i 0]
      (check-cancelled!)
      (println "step" i)
      (recur (inc i)))))

;; let it run a bit…
(Thread/sleep 50)
(cancel! work)

;; downstream:
(async
  (try
    (await work)
    (catch CancellationException _
      (println "Work was cancelled!"))))
```

---

## API Overview

### Task Creation

| Function / Macro | Description                                      |
|------------------|--------------------------------------------------|
| `async`          | Run async orchestration in a core.async `go` block |
| `blocking`       | Run blocking work using virtual threads when available, with a platform-thread fallback |
| `compute`        | Run CPU-heavy work on Clojure's fixed Agent pool |
| `detach`         | Start work outside the current ownership scope   |
| `async-generator`| Create a cold channel-like async source          |
| `yield`          | Publish one value from inside `async-generator`  |
| `->promise-chan` | Coerce supported async values to promise‑chans   |
| `sleep`          | `async` sleep for _ms_                           |
| `defer`          | delay execution by _ms_ then fulfill value or fn |

### Chaining & Composition

| Function             | Description                                                      |
|----------------------|------------------------------------------------------------------|
| `await` / `wait`     | Retrieve result (throws on error)                                |
| `await*` / `wait*`   | Retrieve result or exception as a value (railway style)          |
| `then`               | Run callback on success (skips on error)                         |
| `chain`              | Thread multiple `then` calls (short‑circuits on first error)     |
| `catch`              | Recover from errors of a given type or predicate                 |
| `finally`            | Always run side‑effect on success or error, then re‑deliver      |
| `handle`             | Run handler on outcome (error or success) and deliver its return |
| `error?` / `ok?`     | Predicates to distinguish exception values from normal results   |

### Timeouts & Delays

| Function    | Description                                                                                                                                          |
|-------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| `sleep`     | Asynchronously pause for _ms_ milliseconds; returns a promise‑chan that fulfills with `nil` after the delay.                                         |
| `defer`     | Wait _ms_ milliseconds, then asynchronously deliver a given value or call a provided fn; returns a promise‑chan.                                     |
| `timeout`   | Observe one result with a _ms_ deadline; returns that result or a `TimeoutException`/custom fallback without managing the input source lifetime. |

### Racing & Gathering

| Function      | Description                                                               |
|---------------|---------------------------------------------------------------------------|
| `race`        | First input value or error to settle wins; observes inputs without cancelling losers |
| `any`         | First successful input wins; ignores errors until all fail, then returns an aggregate error |
| `all`         | Wait for all inputs; short‑circuits on first error without cancelling borrowed inputs |
| `all-settled` | Wait for all inputs and return a vector of values and errors without short‑circuiting |

Notes:

- These are Promise-style single-result combinators. A promise-like input contributes its settled value, while a many-valued channel contributes one next take.
- Inputs remain borrowed. The combinators do not call `areturn`, `close!`, or `cancel!` on them; in particular, `timeout` applies to one observed result rather than to a source lifetime.
- Passing a plain value treats it as already available. The current implementation may return the first available plain value, but callers should not rely on any ordering among already available values.
- `all` and `any` short-circuit their returned promise-chan, but that is observation only. They do not cancel borrowed unfinished inputs.
- If every input to `any` errors, it settles with an `ex-info` whose data includes `{:type :all-errored, :errors [...]}`.

### Async Iteration

| Function / Macro  | Description                                                                 |
|-------------------|-----------------------------------------------------------------------------|
| `async-generator` | Cold async source that yields many raw values over a core.async channel      |
| `yield`           | Publish one settled async-style value from inside an async generator         |
| `anext`           | Take one raw value from a channel-like source, or `nil` when done            |
| `areturn`         | Request lifecycle-aware source cleanup and wait for finalization             |
| `adoseq`          | Async `doseq`; consumes sources for side effects and settles to `nil`        |
| `afor`            | Async eager `for`; collects body results into a vector                       |
| `areduce`         | Async reduce with lifecycle-aware early cleanup                             |
| `atransduce`      | Async transduce over a channel-like or collection-like source                |
| `ainto`           | Async `into`, with optional transducer support                               |

### Helpers: `ado`, `alet`, `clet`, `time`

| Macro | Description                                                                                                             |
|-------|-------------------------------------------------------------------------------------------------------------------------|
| `ado` | Asynchronous `do`: execute expressions one after another, awaiting each; returns a promise‑chan of the last expression. |
| `alet`| Asynchronous `let`: like `let` but each binding is awaited in order before evaluating the body.                         |
| `clet`| Concurrent `let`: evaluates all bindings in parallel, auto‑awaiting any dependencies between them.                      |
| `time`| Observation-only timing for a sync expression or one async result; reports elapsed ms and returns the original value. |

For channel-like values, `time` attaches a detached timing observer. Completing or cancelling the caller's scope does not suppress the callback, and timing never closes, cancels, or lifecycle-returns the observed value.

### Asynchronous `let` (`alet`) and Concurrent `let` (`clet`)

Bind async expressions just like a normal `let`—but choose between sequential or parallel evaluation:

| Macro  | Semantics                                                                                                                                   |
|--------|---------------------------------------------------------------------------------------------------------------------------------------------|
| `alet` | **Sequential**: awaits each binding in order before evaluating the next.                                                                    |
| `clet` | **Concurrent**: starts all bindings in parallel, auto‑awaiting any that depend on previous ones while letting independent bindings overlap. |

#### `alet` example

```clojure
(alet
  [a (defer 100 1)
   b (defer 100 2)
   c (defer 100 3)]
  (println "Sum =" (+ a b c)))
;; Prints "Sum = 6" after ~300 ms
```

#### `clet` example

```clojure
(clet
  [a (defer 100 1)
   b (defer a   2)   ;; waits for `a`
   c (defer 100 3)]  ;; independent of `a` and `b`
  (println "Sum =" (+ a b c)))
;; Prints "Sum = 6" after ~200 ms (a and c run in parallel; b waits on a)
```

- Use **`alet`** when bindings must run in sequence.
- Use **`clet`** to maximize concurrency, with dependencies automatically respected.

---

## AI Disclosure

AI has been used as a development tool on this project starting with version
0.2.0. No code in version 0.1.x or earlier was generated with AI. Most of the AI
work since then has used OpenAI GPT-5.5 and GPT-5.6 through Codex, generally at
High or XHigh reasoning effort.

I use AI as an engineering collaborator and accelerator, not as an autonomous
maintainer. For substantial changes, I work spec-first: the implemented system
is described in the current specs, proposed changes are written and questioned
before implementation, and the specs are updated once the code is verified. I
interrogate proposed designs, ask about failure modes and compatibility, review
the resulting code and diffs myself, run the tests, and ask for corrections or
further investigation when something does not make sense. I do not accept a
change merely because the model says it is correct, and I remain responsible
for the design decisions.

For context, I have more than 15 years of professional software-engineering
experience, including over half of that at senior level. I am highly
knowledgeable in Clojure and the JVM and would consider myself an expert in
both. I am also a DevOps engineer with experience designing, implementing,
deploying, and operating backend systems. I have delivered multiple large,
multi-month production projects involving teams of roughly 5–15 engineers.

For this particular library, I understand both the problem space and the
implementation well. I am confident I could have implemented the features
added after 0.1.0 without AI; using it has primarily made exploration,
implementation, testing, documentation, and review more convenient. This
disclosure is here so users can make an informed trust decision.

---

## 📖 Contributing

Contributions, issues, and feature requests are welcome! Feel free to submit a pull request or open an issue on [GitHub](https://github.com/xadecimal/async-style).

---

## 📃 License

Distributed under the MIT License. See [LICENSE](./LICENSE) for more details.

---

## ❤️ Support

If you find `async-style` useful, star ⭐️ the project and share it with the community!
