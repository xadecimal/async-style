# Execution And Cancellation

## Execution Forms

`async`, `blocking`, and `compute` start single-result executions and return
promise channels:

- `async` is for async orchestration and short non-blocking work.
- `blocking` is for blocking I/O and other operations that block a thread.
- `compute` is for heavy or long-running CPU work and must not block.

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

`cancelled?` reports whether the current execution has been cancelled, and
`check-cancelled!` throws an `InterruptedException` at a caller-selected safe
point. Pool interruption may also make `cancelled?` true.

On an async generator channel, `cancel!` requests lifecycle cleanup without
waiting for finalization. `areturn` is the corresponding operation for callers
that must wait for generator cleanup to finish. Raw `close!` is only a channel
close and is not the lifecycle cleanup API.
