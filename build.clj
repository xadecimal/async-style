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
               "Definitions:
    async: asynchronously running on the async-pool, await and others will park, use it for polling and small compute tasks
    blocking: asynchronously running on the blocking-pool, use it for running blocking operations and blocking io
    compute: asynchronously running on the compute-pool, use it for running heavy computation, don't block it
    settle(d): when a channel is delivered a value and closed, or in the case of a promise-chan, it means the promise-chan was fulfilled and will forever return the same value every time it is taken for and additional puts are ignored.
    fulfill(ed): when a channel is delivered a value, but not necessarily closed
    join(ed): when a channel returns a channel, joining is the process of further taking from the returned channel until a value is returned, thus unrolling a channel of channel of channel of ...
    async-pool: the core.async go block executor, it is fixed size, defaulting to 8 threads, don't soft or hard block it
    blocking-pool: the core.async thread block executor, it is caching, unbounded and not pre-allocated, use it for blocking operations and blocking io
    compute-pool: the clojure.core Agent pooledExecutor, it is fixed size bounded to cpu cores + 2 and pre-allocated, use it for heavy computation, don't block it"
               (:refer-clojure :exclude ~'[await time])
               (:require ~'[com.xadecimal.async-style.impl :as impl]
                         ~'[com.xadecimal.async-style.protocols :as proto]))
    :vars [#'impl/error?
           #'impl/ok?
           #'impl/cancelled?
           #'impl/check-cancelled!
           #'impl/cancel!
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
           #'impl/ado
           #'impl/alet
           #'impl/clet
           #'impl/time]}))
