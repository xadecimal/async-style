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
  - [Pools](#pools-async-blocking-compute)
  - [Awaiting](#awaiting-await-wait-await-wait)
  - [Errors](#errors)
  - [Cancellation](#cancellation)
- [API Overview](#api-overview)
- [Helpers: ado, alet, clet, time](#helpers-ado-alet-clet-time)
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
  (println "Divide:" (await (async (/ 1 0))))
  (catch ArithmeticException _
    (println "Can't divide by zero!")))

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
[com.xadecimal/async-style "0.1.0"]
```

### Clojure CLI (deps.edn)

```clojure
{:deps {com.xadecimal/async-style {:mvn/version "0.1.0"}}}
```

---

## Why async-style?

Core.async and the CSP style is powerful, but its `go` blocks and channels can feel low‑level compared to JS Promises and async/await. **async-style** provides:

- **Familiar ergonomics**: `async`/`await` like in JavaScript, Python, C#, etc.
- **Expanded ergonomics**: `blocking`/`wait` for I/O and `compute`/`wait` for heavy compute.
- **Separate pools**: dedicated threads for async control flow (`async`), blocking I/O (`blocking`) and CPU‑bound work (`compute`).
- **Built on core.async**: core.async under the hood, can be used alongside it, promises are core.async's `promise-chan`.
- **First-class error handling**: unlike core.async, errors are bubbled up and properly handled.
- **First‑class cancellation**: propagate cancellation through promise‑chans.
- **Rich composition**: `then`, `chain`, `race`, `all`, `any`, `all-settled`.
- **Convenient macros**: `ado`, `alet`, `clet` for sequential, ordered, or concurrent binding.
- **Railway programming**: supports railway programming with `async`/`await*`, if preferred.

---

## Core Concepts

### Pools: `async`, `blocking`, `compute`

async-style follows the best practice outlined here: [Best practice for async/blocking/compute pools](https://gist.github.com/djspiewak/46b543800958cf61af6efa8e072bfd5c)

Meaning it offers `async`/`blocking`/`compute`, each are meant to specialize the work you will be doing so they get executed on a pool that is most optimal.

#### When used with core.async <= 1.7

| Macro      | Executor pool                           | Use for…                            |
|------------|-----------------------------------------|-------------------------------------|
| `async`    | core.async’s go‑dispatch (8 threads)    | async control flow | light CPU work |
| `blocking` | unbounded cached threads                | blocking I/O, sleeps                |
| `compute`  | fixed agent pool (cores + 2 threads)    | CPU‑intensive work, do not block    |

#### When used with core.async >= 1.8

| Macro      | Executor pool                           | Use for…                            |
|------------|-----------------------------------------|-------------------------------------|
| `async`    | unbounded cached threads                | async control flow | light CPU work |
| `blocking` | unbounded cached threads                | blocking I/O, sleeps                |
| `compute`  | fixed agent pool (cores + 2 threads)    | CPU‑intensive work, do not block    |

#### When used with core.async >= 1.8 with virtual threads

| Macro      | Executor pool                           | Use for…                            |
|------------|-----------------------------------------|-------------------------------------|
| `async`    | virtual thread executor                 | async control flow | light CPU work |
| `blocking` | virtual thread executor                 | blocking I/O, sleeps                |
| `compute`  | fixed agent pool (cores + 2 threads)    | CPU‑intensive work, do not block    |

### Awaiting: `await`, `wait`, `await*`, `wait*`

- **`await`** (inside `async`): parks current go‑thread until a promise‑chan completes; re‑throws errors.
- **`wait`** (outside `async`): blocks calling thread for a result or error.
- **`await*` / `wait*`**: return exceptions as values (no throw).

`await` is like your JavaScript await, but just like core.async's `go`, it cannot park across function boundaries,
so you have to be careful when you use macros like `map` or `run!`, since those use higher-order functions,
you cannot `await` with them. This is true in JavaScript's await as well, but it's less common to use
higher-order functions in JS, so it doesn't feel as restrictive. Once core.async support for virtual thread is added, and if you run under a JDK that supports them, this limitation will go away.

`wait` is the counter-part to `await`, it is like `await` but synchronous. It doesn't color your functions, but will block your thread.

`await*` / `wait*` are variants that return the exception as a value, instead of throwing.

### Errors

#### Implicit `try` / `catch` / `finally`

All of `async`, `blocking`, `compute` and `await`:

1. **Automatically wrap** your body in a single `try` if you include any trailing
   `(catch …)` or `(finally …)` forms.
2. **Pull in** any `(catch Type e …)` or `(finally …)` at the end of your block
   into that `try`, so you don’t have to write it yourself.

```clojure
;; without implicit try, you’d need:
(async
  (try
    (/ 1 0)
    (catch ArithmeticException e
      (println "oops:" e))
    (finally
      (println "done"))))

;; with implicit try, just append catch/finally:
(async
  (/ 1 0)
  (catch ArithmeticException e
    (println "oops:" e))
  (finally
    (println "done")))
```

- Missing catch/finally? No wrapping is done (no overhead).
- Only catch or only finally? Works the same.
- Multiple catch? All are honored in order.

This gives you JS‑style inline error handling right in your async blocks.

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

Errors are always modeled this way in async-chan, unlike in JS which wraps errors in a map, in async-style they are either an error? or an ok? result:

```clojure
(async
  (let [vals (await* (all-settled [(async 1) (async (/ 1 0)) (async 3)]))]
    (println
      (map (fn [v] (if (error? v) :err v)) vals))))
;; ⇒ (1 :err 3)
```

### Cancellation

Cancellation in **async-style** is **cooperative**—you signal it with `cancel!`, but your code must check for it to actually stop.

- **`cancel!`**
  ```clojure
  (cancel! ch)              ; cancel with CancellationException
  (cancel! ch custom-val)   ; cancel with custom-val (must not be nil)
  ```
  Marks the promise‑chan `ch` as cancelled. If the block hasn’t started, it immediately fulfills; otherwise it waits for you to check.

- **`cancelled?`**
  ```clojure
  (when-not (cancelled?) …)
  ```
  Returns `true` inside `async`/`blocking`/`compute` if `cancel!` was called. Does **not** throw.

- **`check-cancelled!`**
  ```clojure
  (check-cancelled!)  ; throws CancellationException if cancelled
  ```
  Throws immediately when cancelled, so you can use it at safe points to short‑circuit heavy loops or I/O.

- **Handling cancellation downstream**
  - `await` / `wait` will re‑throw the `CancellationException` (or return your custom val).

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
  (await work)
  (catch CancellationException _
    (println "Work was cancelled!")))
```

---

## API Overview

### Task Creation

| Function / Macro | Description                                      |
|------------------|--------------------------------------------------|
| `async`          | Run code on the async‑pool                       |
| `blocking`       | Run code on blocking‑pool                        |
| `compute`        | Run code on compute‑pool                         |
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
| `timeout`   | Wrap a channel with a _ms_ deadline—if it doesn’t fulfill in time, cancels the original and delivers a `TimeoutException` (or your custom fallback). |

### Racing & Gathering

| Function      | Description                                                               |
|---------------|---------------------------------------------------------------------------|
| `race`        | First chan (or error) to complete “wins”; cancels all others              |
| `any`         | First **successful** chan; ignores errors; errors aggregated if all fail  |
| `all`         | Wait for all; short‑circuits on first error; returns vector of results    |
| `all-settled` | Wait for all, return vector of all results or errors                      |

### Helpers: `ado`, `alet`, `clet`, `time`

| Macro | Description                                                                                                      |
|-------|------------------------------------------------------------------------------------------------------------------|
| `ado` | Execute expressions one after another, awaiting each; returns a promise‑chan of the last expression.             |
| `alet`| Like `let` but each binding is awaited in order before evaluating the body.                                      |
| `clet`| Concurrent `let`: evaluates all bindings in parallel, auto‑awaiting any dependencies between them.               |
| `time`| Measure wall‑clock time of a sync or async expression; prints the elapsed ms and returns the expression’s value. |

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

## 📖 Contributing

Contributions, issues, and feature requests are welcome! Feel free to submit a pull request or open an issue on [GitHub](https://github.com/xadecimal/async-style).

---

## 📃 License

Distributed under the MIT License. See [LICENSE](./LICENSE) for more details.

---

## ❤️ Support

If you find `async-style` useful, star ⭐️ the project and share it with the community!
