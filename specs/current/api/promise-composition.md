# Promise Composition And Time

## Result Combinators

The library provides these single-result combinators:

- `then` runs a callback for an ok result and preserves an error unchanged.
- `chain` applies multiple `then` callbacks in order.
- `catch` handles matching errors and preserves other results.
- `finally` invokes a callback for either outcome and then redelivers the
  original outcome.
- `handle` invokes one callback for either outcome and delivers the callback's
  result.

Callbacks use async-style value semantics, so supported returned async values
are assimilated before the combinator settles.

## Racing And Gathering

`race`, `any`, `all`, and `all-settled` are Promise-style single-result
combinators. A promise-like input contributes its settled result. A many-value
channel contributes one next take.

- `race` returns the first available value or error and consumes only the
  winning take from channel inputs.
- `any` returns the first ok result. If all inputs error, it returns an
  `ex-info` with `:type :all-errored` and the collected errors.
- `all` observes inputs concurrently, preserves input order, and settles on
  the first error or on the vector of ok results.
- `all-settled` observes inputs concurrently, preserves input order, and
  returns every value and error without short-circuiting.

These combinators borrow their inputs. They do not call `cancel!`, `close!`, or
`areturn` on inputs or otherwise take ownership of them. A locally started
input may still be cancelled later by its actual owning scope.

## Time Operations

`sleep` returns a promise channel that settles after a delay. `defer` waits for
a delay and then delivers a supplied value or calls a supplied function.

`timeout` observes one result until a deadline. It returns that result, a
`TimeoutException`, or a caller-supplied fallback. Timing out cancels the
pending observation machinery but does not manage the lifetime of the observed
input.

`time` reports elapsed milliseconds and preserves the observed value. For
channel-like values, it installs detached observation so parent completion or
cancellation does not suppress the timing report. It does not close, cancel,
or lifecycle-return the observed input.

## Binding Helpers

`ado` evaluates and awaits expressions sequentially and returns the last
result. `alet` evaluates and awaits bindings sequentially. `clet` starts
independent bindings concurrently and waits for bindings whose expressions
depend on earlier local symbols before starting their dependents. When `clet`
runs inside an async-style scope, its binding computations are ordinary owned
children rather than detached background work.
