# Execution And Cancellation

## Execution Forms

`async`, `blocking`, and `compute` start single-result executions and return
promise channels. They select execution contexts by workload:

- `async` runs as a `core.async` `go` block. It is for async orchestration,
  parking with `await`, polling, and short non-blocking work. It must not perform
  blocking I/O, call blocking waits, or run sustained CPU-heavy work. Modern
  `core.async` continues to execute `go` blocks through its IOC machinery on a
  platform-thread dispatch executor; availability of virtual threads does not
  change `async` into a virtual-thread execution.
- `blocking` runs as `core.async` `io-thread` when that API is available and
  falls back to `core.async` `thread` on older versions. With current
  `core.async` on Java 21 or newer, each `io-thread` task uses a virtual thread.
  When virtual threads are unavailable, and on the supported `core.async` 1.7
  compatibility path, it uses a cached platform-thread executor. It is for
  blocking I/O, sleeps, and other operations that block a thread, not sustained
  CPU-heavy work.
- `compute` runs on Clojure's fixed Agent pooled executor, whose size is the
  available processor count plus two platform threads. It is for heavy or
  long-running CPU work and must not block, park, or wait.

Executor factories and JVM capabilities may change the exact underlying
executor, but they do not change these workload contracts.

Each form reserves a leading literal options map when another body form
follows. The only accepted options map is currently empty. A non-empty options
map fails during macro expansion, while a sole map remains an ordinary result
value.

The body uses ordinary Clojure `try`, `catch`, and `finally` syntax. The
execution and waiting macros do not implement implicit trailing handler forms.

## Waiting And Errors

`await` parks within async-compatible code and `wait` blocks the calling
thread. Both accept one value and throw a resulting `Throwable`.

`await*` and `wait*` use the same waiting behavior but return a `Throwable` as
an ordinary result. `error?` recognizes `Throwable` values; `ok?` recognizes
all other values.

Channel-like inputs are explicitly taken. When an execution settles to a raw
or generator source channel as its value, waiting on that execution returns the
source channel itself rather than consuming it recursively.

## Promise Coercion And Settlement

`->promise-chan` preserves channel inputs, observes `Future`,
`CompletableFuture`, and other `IBlockingDeref` values through detached
blocking work, and passes unsupported plain values through unchanged.
Cancelling a coerced future attempts to cancel the underlying future when its
type supports cancellation.

An `async`, `blocking`, or `compute` producer assimilates one returned
promise-like single-result value before settling. Nested async-style producers
compose because each producer performs this one-level assimilation.
Multi-value source channels are preserved as values and are not started or
consumed by producer settlement.

## Ownership

Each running execution is a cancellation scope. Starting `async`, `blocking`,
or `compute` inside that scope creates a direct owned child. Ending the parent
by success, failure, or cancellation cancels unfinished direct children.
Cancellation becomes transitive because every child applies the same rule to
its own children.

Awaiting or composing already-started work is borrowed observation and does not
transfer ownership. Internal watchers may belong to an operation, but the
values they watch remain borrowed.

`detach` temporarily removes the current ownership context. Work started
inside it may outlive the surrounding execution and is not cancelled when that
execution ends.

## Cancellation API

`cancel!` settles or signals a single-result operation with a non-nil
cancellation value. Without an explicit value it uses a
`CancellationException`. Cancellation is cooperative for running user code.

Cancelling a running `blocking` or `compute` execution also interrupts its
worker thread. This stops interruptible blocking operations, but code that
swallows or does not respond to interruption can remain running. A top-level or
detached blocking execution therefore remains an explicit thread-lifetime task;
dropping its result channel does not make the running thread collectible.

`cancelled?` reports whether the current execution has been cancelled, and
`check-cancelled!` throws an `InterruptedException` at a caller-selected safe
point. Pool interruption may also make `cancelled?` true.

On an async generator channel, `cancel!` requests lifecycle cleanup without
waiting for finalization. `areturn` is the corresponding operation for callers
that must wait for generator cleanup to finish. Raw `close!` is only a channel
close and is not the lifecycle cleanup API.
