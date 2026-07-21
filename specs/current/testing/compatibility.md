# Testing And Compatibility

## Supported Dependency Sets

The default dependency set uses Clojure 1.12.5 and `core.async` 1.9.865. The
`:test-1.7` alias overrides `core.async` with version 1.7.701 to validate the
older executor and channel behavior supported by the implementation.

## Verification Commands

- `clojure -M:test` runs the suite with the default dependencies.
- `bb test` runs both the default suite and the `core.async` 1.7 compatibility
  suite.
- `bb test-vars` runs selected fully qualified test vars.
- `bb lint` runs the build configuration lint task.
- `bb gen` regenerates the public namespace.
- `bb release` runs linting, generation, tests, and local installation.

Behavioral tests live in `test/com/xadecimal/async_style_test.clj`. They cover
success and error results, coercion, cancellation ownership, Promise-style
composition, generator lifecycle behavior, iteration, helper macros, and both
supported `core.async` dependency sets.
