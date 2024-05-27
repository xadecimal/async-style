(ns com.xadecimal.async-style
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
  (:refer-clojure :exclude [await time])
  (:require [com.xadecimal.async-style.impl :as impl]))


(def ^{:doc (-> #'impl/error? meta :doc)
       :arglists (-> #'impl/error? meta :arglists)}
  error?
  impl/error?)

(def ^{:doc (-> #'impl/ok? meta :doc)
       :arglists (-> #'impl/ok? meta :arglists)}
  ok?
  impl/ok?)

(def ^{:doc (-> #'impl/cancelled? meta :doc)
       :arglists (-> #'impl/cancelled? meta :arglists)}
  cancelled?
  impl/cancelled?)

(def ^{:doc (-> #'impl/cancel meta :doc)
       :arglists (-> #'impl/cancel meta :arglists)}
  cancel
  impl/cancel)

(def ^{:doc (-> #'impl/<<! meta :doc)
       :arglists (-> #'impl/<<! meta :arglists)
       :macro true}
  <<!
  (var-get #'impl/<<!))

(def ^{:doc (-> #'impl/<<!! meta :doc)
       :arglists (-> #'impl/<<!! meta :arglists)}
  <<!!
  impl/<<!!)

(def ^{:doc (-> #'impl/<<? meta :doc)
       :arglists (-> #'impl/<<? meta :arglists)
       :macro true}
  <<?
  (var-get #'impl/<<?))

(def ^{:doc (-> #'impl/<<?? meta :doc)
       :arglists (-> #'impl/<<?? meta :arglists)
       :macro true}
  <<??
  (var-get #'impl/<<??))

(def ^{:doc (-> #'impl/ex-details meta :doc)
       :arglists (-> #'impl/ex-details meta :arglists)}
  ex-details
  impl/ex-details)

(def ^{:doc (-> #'impl/async meta :doc)
       :arglists (-> #'impl/async meta :arglists)
       :macro true}
  async
  (var-get #'impl/async))

(def ^{:doc (-> #'impl/blocking meta :doc)
       :arglists (-> #'impl/blocking meta :arglists)
       :macro true}
  blocking
  (var-get #'impl/blocking))

(def ^{:doc (-> #'impl/compute meta :doc)
       :arglists (-> #'impl/compute meta :arglists)
       :macro true}
  compute
  (var-get #'impl/compute))

(def ^{:doc (-> #'impl/await meta :doc)
       :arglists (-> #'impl/await meta :arglists)
       :macro true}
  await
  (var-get #'impl/await))

(def ^{:doc (-> #'impl/catch meta :doc)
       :arglists (-> #'impl/catch meta :arglists)}
  catch
  impl/catch)

(def ^{:doc (-> #'impl/finally meta :doc)
       :arglists (-> #'impl/finally meta :arglists)}
  finally
  impl/finally)

(def ^{:doc (-> #'impl/then meta :doc)
       :arglists (-> #'impl/then meta :arglists)}
  then
  impl/then)

(def ^{:doc (-> #'impl/chain meta :doc)
       :arglists (-> #'impl/chain meta :arglists)}
  chain
  impl/chain)

(def ^{:doc (-> #'impl/handle meta :doc)
       :arglists (-> #'impl/handle meta :arglists)}
  handle
  impl/handle)

(def ^{:doc (-> #'impl/sleep meta :doc)
       :arglists (-> #'impl/sleep meta :arglists)}
  sleep
  impl/sleep)

(def ^{:doc (-> #'impl/defer meta :doc)
       :arglists (-> #'impl/defer meta :arglists)}
  defer
  impl/defer)

(def ^{:doc (-> #'impl/timeout meta :doc)
       :arglists (-> #'impl/timeout meta :arglists)}
  timeout
  impl/timeout)

(def ^{:doc (-> #'impl/race meta :doc)
       :arglists (-> #'impl/race meta :arglists)}
  race
  impl/race)

(def ^{:doc (-> #'impl/any meta :doc)
       :arglists (-> #'impl/any meta :arglists)}
  any
  impl/any)

(def ^{:doc (-> #'impl/all-settled meta :doc)
       :arglists (-> #'impl/all-settled meta :arglists)}
  all-settled
  impl/all-settled)

(def ^{:doc (-> #'impl/all meta :doc)
       :arglists (-> #'impl/all meta :arglists)}
  all
  impl/all)

(def ^{:doc (-> #'impl/do! meta :doc)
       :arglists (-> #'impl/do! meta :arglists)
       :macro true}
  do!
  (var-get #'impl/do!))

(def ^{:doc (-> #'impl/alet meta :doc)
       :arglists (-> #'impl/alet meta :arglists)
       :macro true}
  alet
  (var-get #'impl/alet))

(def ^{:doc (-> #'impl/clet meta :doc)
       :arglists (-> #'impl/clet meta :arglists)
       :macro true}
  clet
  (var-get #'impl/clet))

(def ^{:doc (-> #'impl/time meta :doc)
       :arglists (-> #'impl/time meta :arglists)
       :macro true}
  time
  (var-get #'impl/time))
