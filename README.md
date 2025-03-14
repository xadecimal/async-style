# async-style

## Overview

`async-style` is a Clojure library that ports JavaScript's async/await and promise APIs to Clojure, along with additional utilities to facilitate asynchronous programming. This library provides a set of macros and functions to manage asynchronous tasks, handling blocking operations, computations, and exception management in a clean and efficient manner.

## Installation

### Leiningen

Add the following dependency to your `project.clj`:

```clojure
[com.xadecimal/async-style "0.1.0"]
```

### Clojure CLI/deps.edn

Add the following dependency to your `deps.edn`:

```clojure
{:deps {com.xadecimal/async-style {:mvn/version "0.1.0"}}}
```

## Usage

The library provides a rich set of functions and macros to help you manage asynchronous tasks. Below are some of the key features and usage examples:

### Example Usage

```clojure
(ns example.core
  (:require [com.xadecimal.async-style :as async]))

(async/async
  (let [result (async/await (async/async (+ 1 2)))]
    (println "Result:" result)))

(async/blocking
  (Thread/sleep 1000)
  (println "Done blocking!"))

(async/compute
  (let [factorial (reduce * (range 1 10))]
    (println "Factorial:" factorial)))

;; Using cancel
(let [c (async/async (Thread/sleep 5000)
                     (println "This will never print"))]
  (Thread/sleep 1000)
  (async/cancel c "Operation cancelled"))
```

### Basic Concepts

- **async**: Executes code asynchronously on the async-pool, suitable for small compute tasks or polling.
- **blocking**: Executes code asynchronously on the blocking-pool, ideal for blocking operations or I/O.
- **compute**: Executes code asynchronously on the compute-pool, meant for heavy computation tasks.
- **await**: Waits for the result of an asynchronous operation, re-throwing any exceptions encountered.
- **cancel**: Cancels an asynchronous operation, ensuring it doesn't complete.

### API Documentation

#### Error Handling
- **`error?`**: Returns true if the value is considered an error.
- **`ok?`**: Returns true if the value is not considered an error.

#### Cancellation
- **`cancel`**: Cancels an asynchronous operation.
- **`cancelled?`**: Checks if the current execution context has been cancelled.

#### Asynchronous Operations
- **`async`**: Executes code asynchronously on the async-pool.
- **`blocking`**: Executes code asynchronously on the blocking-pool.
- **`compute`**: Executes code asynchronously on the compute-pool.

#### Await and Chain
- **`await`**: Waits for the result of an asynchronous operation.
- **`then`**: Chains an operation to be executed after another completes.
- **`chain`**: Chains multiple operations together.

### Advanced Features

#### Timeout and Sleep
- **`timeout`**: Sets a timeout on an asynchronous operation.
- **`sleep`**: Asynchronously sleeps for a specified duration.

#### Combining Tasks
- **`race`**: Returns the result of the first task to complete.
- **`any`**: Returns the first successful result from a list of tasks.
- **`all`**: Waits for all tasks to complete and returns their results.
- **`all-settled`**: Waits for all tasks to complete, returning both successful and failed results.

### Testing

The library includes a suite of tests to ensure its robustness. You can run the tests using:

```clojure
lein test
```

Or if using `deps.edn`:

```clojure
clj -X:test
```

### Contributing

Contributions are welcome! Please submit issues and pull requests via [GitHub](https://github.com/xadecimal/async-style).

### License

This project is licensed under the MIT License. See the [LICENSE](./LICENSE) file for details.

---

For more detailed examples and advanced usage, refer to the [documentation](https://github.com/xadecimal/async-style).
