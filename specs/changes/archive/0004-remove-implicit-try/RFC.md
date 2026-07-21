# RFC: Remove Implicit Try Syntax

## Status

Completed

Archive note: This RFC supersedes the implicit trailing `catch` / `finally`
requirements recorded in the async-iteration RFC and its implementation notes.

## Summary

Remove async-style's implicit trailing `catch` / `finally` syntax from all
public APIs. After this change, `catch` and `finally` forms will only have their
ordinary Clojure meaning inside an explicit `try`.

This keeps `async`, `blocking`, `compute`, `async-generator`, `await`, and
`wait` focused on execution and value semantics rather than also parsing a
small custom error-handling language.

## Current Behavior

Today several forms accept trailing `catch` and `finally` forms and rewrite the
body as though it were wrapped in a `try`.

For example:

```clojure
(async
  (await work)
  (catch Throwable t
    (fallback t))
  (finally
    (record-finished)))
```

is treated like:

```clojure
(async
  (try
    (await work)
    (catch Throwable t
      (fallback t))
    (finally
      (record-finished))))
```

The same idea is available in `blocking`, `compute`, `async-generator`,
`await`, and `wait`.

## Motivation

Implicit try syntax primarily avoids one level of nesting. That convenience is
real, but it has started to look like the wrong tradeoff for a v1 API.

The syntax has important limits:

- Trailing `catch` and `finally` forms cannot see locals introduced inside the
  body.
- This especially limits `finally`, where the common use case is cleaning up a
  value acquired in the body.
- It does not support retry or other control-flow policies without adding more
  custom trailing syntax.
- It makes the tail position of multiple macros special, so forms that look
  like ordinary code can instead be parsed as directives.
- It complicates future API growth for `async`, `blocking`, `compute`, and
  `async-generator`.

Because the library is still pre-v1, this is the best time to remove the
special case and make the stable surface smaller and more composable.

## Proposal

Remove implicit try parsing from:

- `async`
- `blocking`
- `compute`
- `async-generator`
- `await`
- `wait`

After removal, users should write explicit `try` when they need `catch` or
`finally`:

```clojure
(async
  (try
    (await work)
    (catch Throwable t
      (fallback t))
    (finally
      (record-finished))))
```

For resource cleanup where cleanup needs access to acquired locals, users should
use ordinary lexical Clojure structure:

```clojure
(async-generator
  (with-open [rdr (clojure.java.io/reader path)]
    (doseq [line (line-seq rdr)]
      (yield line))))
```

or:

```clojure
(async-generator
  (let [conn (await (blocking (open-conn)))]
    (try
      (adoseq [row (rows conn)]
        (yield row))
      (finally
        (await (blocking (.close conn)))))))
```

## Non-Goals

This RFC does not introduce replacement APIs.

In particular, it does not define:

- `try-let`
- `with-resource` or `with-resources`
- `acquire-release`
- `retry` or `retrying`
- new option-map keys for `async`, `blocking`, `compute`, or `async-generator`

Those may be considered in separate focused RFCs.

## Semantics After Removal

The affected macros should no longer inspect trailing body forms for symbols
named `catch` or `finally`.

A form such as this should no longer compile as async-style implicit syntax:

```clojure
(async
  (await work)
  (catch Throwable t
    (fallback t)))
```

Users must write:

```clojure
(async
  (try
    (await work)
    (catch Throwable t
      (fallback t))))
```

Existing async-style helper functions named `catch` and `finally` can remain as
ordinary value-level combinators unless a separate RFC removes or renames them.
This RFC only removes implicit trailing block syntax.

## Migration

Mechanical migration is straightforward:

1. Find `async`, `blocking`, `compute`, `async-generator`, `await`, and `wait`
   forms that end in `catch` or `finally`.
2. Wrap the non-handler body forms in an explicit `try`.
3. Move the trailing handler forms into that `try`.
4. For cleanup that needs body-local values, prefer moving the `try` inside the
   binding form instead of trying to preserve the old outer shape.

For example:

```clojure
(async
  (let [v (await work)]
    (use v))
  (finally
    (record-finished)))
```

becomes:

```clojure
(async
  (try
    (let [v (await work)]
      (use v))
    (finally
      (record-finished))))
```

When cleanup needs `v`, use lexical nesting:

```clojure
(async
  (let [v (await work)]
    (try
      (use v)
      (finally
        (cleanup v)))))
```

## Compatibility

This is a breaking source change. It should be done before v1.

The generated public namespace and documentation must be updated with the
implementation change.
