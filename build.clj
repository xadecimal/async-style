(ns build
  (:require [com.xadecimal.async-style.impl :as impl]
            [com.xadecimal.expose-api :as ea]
            [build-edn.main :as build-edn]
            [com.xadecimal.async-style.protocols :as proto]))

(defn lint
  [m]
  (build-edn/lint m))

(defn deploy
  [m]
  (build-edn/deploy m))

(defn install
  [m]
  (build-edn/install m))

(defn update-documents
  [m]
  (build-edn/update-documents m))

(defn bump-major-version
  [m]
  (build-edn/bump-major-version m))

(defn bump-minor-version
  [m]
  (build-edn/bump-minor-version m))

(defn bump-patch-version
  [m]
  (build-edn/bump-patch-version m))

(defn gen
  [m]
  (ea/expose-api
   {:file-path "./src/com/xadecimal/async_style.clj"
    :ns-code `(~'ns ~'com.xadecimal.async-style
               "Async/await-style concurrency and async iteration built on core.async.

Choosing an execution:
    async: use for async control flow, polling, and small or short computations; work may park, but must not block
    compute: use for heavy or long-running computation; do not block
    blocking: use for blocking I/O and other operations that block the current thread
    Executor implementations vary with the core.async version and JVM configuration.

Core terms:
    settle(d): deliver one result and close the channel; settling nil closes without delivering because core.async channels cannot contain nil
    fulfill(ed): deliver a value without closing the channel, leaving it open for additional values
    promise-chan: a single-result core.async channel used for async-style execution results and cooperative cancellation
    producer settlement: async, blocking, and compute wait for one returned promise-like single-result value before settling their own result; returned multi-value source channels remain channel values
    ownership: starting async, blocking, or compute inside a running execution creates an owned child unless started inside detach; ending the parent cancels unfinished owned children
    observation: awaiting or composing already-started work borrows it without transferring ownership or cancelling it
    async source: async-generator returns a cold, lifecycle-aware multi-value channel; consumption starts its producer in the consuming scope, and channel close or a nil take means done"
               (:refer-clojure :exclude ~'[areduce await time])
               (:require ~'[com.xadecimal.async-style.impl :as impl]
                         ~'[com.xadecimal.async-style.protocols :as proto]))
    :vars [#'impl/error?
           #'impl/ok?
           #'impl/cancelled?
           #'impl/check-cancelled!
           #'impl/cancel!
           #'impl/detach
           #'proto/->promise-chan
           #'impl/await*
           #'impl/wait*
           #'impl/async
           #'impl/blocking
           #'impl/compute
           #'impl/await
           #'impl/wait
           #'impl/catch
           #'impl/finally
           #'impl/then
           #'impl/chain
           #'impl/handle
           #'impl/sleep
           #'impl/defer
           #'impl/timeout
           #'impl/race
           #'impl/any
           #'impl/all-settled
           #'impl/all
           #'impl/async-generator
           #'impl/yield
           #'impl/anext
           #'impl/areturn
           #'impl/adoseq
           #'impl/afor
           #'impl/areduce
           #'impl/atransduce
           #'impl/ainto
           #'impl/ado
           #'impl/alet
           #'impl/clet
           #'impl/time]}))
