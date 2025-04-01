(ns com.xadecimal.async-style-test
  (:refer-clojure :exclude [await time])
  (:require [clojure.test :refer [deftest is]]
            [com.xadecimal.async-style :as a :refer :all]
            [com.xadecimal.testa :refer [testa q! dq!]])
  (:import [java.lang AssertionError]
           [java.util.concurrent CancellationException TimeoutException]
           [clojure.core.async.impl.channels ManyToManyChannel]))


(deftest error?-tests
  (testa "Tests if something is considered an async-style error."
         (is (error? (ex-info "" {})))
         (is (not (error? 10))))

  (testa "All Throwables are considered async-style errors, and will count as an
error when returned by an async block, or any promise-chan."
         (is (error? (try (throw (Throwable.))
                          (catch Throwable t
                            t))))))


(deftest ok?-tests
  (testa "Tests if something is not an async-style error?"
         (is (ok? 10))
         (is (ok? "hello"))
         (is (ok? :error))
         (is (not (ok? (try (throw (Throwable.))
                            (catch Throwable t
                              t)))))))


(deftest cancelled?-tests
  (testa "Async-style supports cancellation, you have to explicitly check for
cancelled? inside your async blocks."
         (-> (async (loop [i 100 acc 0]
                      (if (or (pos? i) (cancelled?))
                        (recur (dec i) (+ acc i))
                        acc)))
             (handle q!))
         (is (= 5050 (dq!))))

  (testa "When used outside an async block, it throws."
         (is (thrown? IllegalArgumentException (cancelled?))))

  (testa "Returns true if cancelled."
         (let [promise-chan (async (Thread/sleep 60)
                                   (q! (cancelled?)))]
           (Thread/sleep 30)
           (cancel promise-chan))
         (is (dq!)))

  (testa "False otherwise."
         (async (q! (cancelled?)))
         (is (not (dq!)))))


(deftest cancel-tests
  (testa "You can use cancel to cancel an async block, this is best effort.
If the block has not started executing yet, it will cancel, otherwise it
needs to be the async block explicitly checks for cancelled? at certain
points in time and short-circuit/interrupt, or cancel will not be able to
actually cancel."
         (let [promise-chan (async "I am cancelled")]
           (cancel promise-chan)
           (is (= CancellationException (type (<<!! promise-chan))))))

  (testa "If the block has started executing, it won't cancel without explicit
cancellation? check."
         (let [promise-chan (async "I am not cancelled")]
           (Thread/sleep 30)
           (cancel promise-chan)
           (is (= "I am not cancelled" (<<!! promise-chan)))))

  (testa "If the block checks for cancellation explicitly, it can still be
cancelled."
         (let [promise-chan (async (Thread/sleep 100)
                                   "I am cancelled")]
           (Thread/sleep 30)
           (cancel promise-chan)
           (is (= CancellationException (type (<<!! promise-chan))))))

  (testa "A value can be specified when cancelling, which is returned by the
promise-chan of the async block instead of returning a CancellationException.
Note that it is returned wrapped inside a reduced to indicate the short-circuiting."
         (let [promise-chan (async (Thread/sleep 100)
                                   "I am cancelled")]
           (Thread/sleep 30)
           (cancel promise-chan "We had to cancel this.")
           (is (reduced? (<<!! promise-chan)))
           (is (= "We had to cancel this." @(<<!! promise-chan)))))

  (testa "It will not wrap a given reduced in another reduced, effectively it
behaves like ensure-reduced."
         (let [promise-chan (async (Thread/sleep 100)
                                   "I am cancelled")]
           (Thread/sleep 30)
           (cancel promise-chan (reduced "We had to cancel this."))
           (is (reduced? (<<!! promise-chan)))
           (is (= "We had to cancel this." @(<<!! promise-chan))))))


(deftest <<!-tests
  (testa "Takes a value from a chan."
         (async
           (q! (<<! (async "Hello!"))))
         (is (= "Hello!" (dq!))))

  (testa "Parks if none are available yet, resumes only once a value is available."
         (async
           (let [pc (async (Thread/sleep 100)
                           "This will only have a value after 100ms")]
             (q! (System/currentTimeMillis))
             (q! (<<! pc))
             (q! (System/currentTimeMillis))))
         (let [before-time (dq!)
               ret (dq!)
               after-time (dq!)]
           (is (>= (- after-time before-time) 100))
           (is (= "This will only have a value after 100ms" ret))))

  (testa "Throws if used outside an async block"
         (is (thrown? AssertionError (<<! (async)))))

  (testa "Works on values as well, will just return it immediately."
         (async (q! (<<! :a-value))
                (q! (<<! 1))
                (q! (<<! ["a" "b"]))
                (q! (System/currentTimeMillis))
                (q! (<<! :a-value))
                (q! (System/currentTimeMillis)))
         (is (= :a-value (dq!)))
         (is (= 1 (dq!)))
         (is (= ["a" "b"] (dq!)))
         (let [before-time (dq!)
               _ (dq!)
               after-time (dq!)]
           (is (<= (- after-time before-time) 1))))

  (testa "If an exception is returned by chan, it will return the exception,
it won't throw."
         (async
           (q! (<<! (async (/ 1 0)))))
         (is (= ArithmeticException (type (dq!)))))

  (testa "Taken value will be fully joined. That means if the value taken is
itself a chan, <<! will also take from it, until it eventually takes a non chan
value."
         (async
           (q! (<<! (async (async (async 1))))))
         (is (= 1 (dq!)))))


(deftest <<!!-tests
  (testa "Takes a value from a chan."
         (is (= "Hello!" (<<!! (async "Hello!")))))

  (testa "Blocks if none are available yet, resumes only once a value is available."
         (let [pc (async (Thread/sleep 100)
                         "This will only have a value after 100ms")
               before-time (System/currentTimeMillis)
               ret (<<!! pc)
               after-time (System/currentTimeMillis)]
           (is (>= (- after-time before-time) 100))
           (is (= "This will only have a value after 100ms" ret))))

  (testa "Should not be used inside an async block, since it will block instead
of parking."
         (async (q! (<<!! (async "Don't do this even though it works."))))
         (is (= "Don't do this even though it works." (dq!))))

  (testa "Works on values as well, will just return it immediately."
         (is (= :a-value (<<!! :a-value)))
         (is (= 1 (<<!! 1)))
         (is (= ["a" "b"] (<<!! ["a" "b"])))
         (let [before-time (System/currentTimeMillis)
               _ (<<!! :a-value)
               after-time (System/currentTimeMillis)]
           (is (<= (- after-time before-time) 1))))

  (testa "If an exception is returned by chan, it will return the exception,
it won't throw."
         (is (= ArithmeticException (type (<<!! (async (/ 1 0)))))))

  (testa "Taken value will be fully joined. That means if the value taken is
itself a chan, <<!! will also take from it, until it eventually takes a non chan
value."
         (is (= 1 (<<!! (async (async (async 1))))))))


(deftest <<?-tests
  (testa "Takes a value from a chan."
         (async
           (q! (<<? (async "Hello!"))))
         (is (= "Hello!" (dq!))))

  (testa "Parks if none are available yet, resumes only once a value is available."
         (async
           (let [pc (async (Thread/sleep 100)
                           "This will only have a value after 100ms")]
             (q! (System/currentTimeMillis))
             (q! (<<? pc))
             (q! (System/currentTimeMillis))))
         (let [before-time (dq!)
               ret (dq!)
               after-time (dq!)]
           (is (>= (- after-time before-time) 100))
           (is (= "This will only have a value after 100ms" ret))))

  (testa "Throws if used outside an async block"
         (is (thrown? AssertionError (<<? (async)))))

  (testa "Works on values as well, will just return it immediately."
         (async (q! (<<? :a-value))
                (q! (<<? 1))
                (q! (<<? ["a" "b"]))
                (q! (System/currentTimeMillis))
                (q! (<<? :a-value))
                (q! (System/currentTimeMillis)))
         (is (= :a-value (dq!)))
         (is (= 1 (dq!)))
         (is (= ["a" "b"] (dq!)))
         (let [before-time (dq!)
               _ (dq!)
               after-time (dq!)]
           (is (<= (- after-time before-time) 1))))

  (testa "If an exception is returned by chan, it will re-throw the exception."
         (async (try
                  (<<? (async (/ 1 0)))
                  (catch ArithmeticException _
                    (q! :thrown))))
         (is (= :thrown (dq!))))

  (testa "Taken value will be fully joined. That means if the value taken is
itself a chan, <<? will also take from it, until it eventually takes a non chan
value."
         (async
           (q! (<<? (async (async (async 1))))))
         (is (= 1 (dq!)))))


(deftest <<??-tests
  (testa "Takes a value from a chan."
         (is (= "Hello!" (<<?? (async "Hello!")))))

  (testa "Blocks if none are available yet, resumes only once a value is available."
         (let [pc (async (Thread/sleep 100)
                         "This will only have a value after 100ms")
               before-time (System/currentTimeMillis)
               ret (<<?? pc)
               after-time (System/currentTimeMillis)]
           (is (>= (- after-time before-time) 100))
           (is (= "This will only have a value after 100ms" ret))))

  (testa "Should not be used inside an async block, since it will block instead
of parking."
         (async (q! (<<?? (async "Don't do this even though it works."))))
         (is (= "Don't do this even though it works." (dq!))))

  (testa "Works on values as well, will just return it immediately."
         (is (= :a-value (<<?? :a-value)))
         (is (= 1 (<<?? 1)))
         (is (= ["a" "b"] (<<?? ["a" "b"])))
         (let [before-time (System/currentTimeMillis)
               _ (<<?? :a-value)
               after-time (System/currentTimeMillis)]
           (is (<= (- after-time before-time) 1))))

  (testa "If an exception is returned by chan, it will re-throw the exception."
         (is (thrown? ArithmeticException (<<?? (async (/ 1 0))))))

  (testa "Taken value will be fully joined. That means if the value taken is
itself a chan, <<?? will also take from it, until it eventually takes a non chan
value."
         (is (= 1 (<<?? (async (async (async 1))))))))


(deftest wait-tests
  (testa "Wait is used to wait on the result of an async operation in a
synchronous manner, meaning it will block the waiting thread. The upside
is that unlike await, it can be used outside of an async context."
         (is (= 3 (-> (async (+ 1 2))
                      (wait)))))
  (testa "Takes a value from a chan."
         (is (= "Hello!" (wait (async "Hello!")))))

  (testa "Blocks if none are available yet, resumes only once a value is available."
         (let [pc (async (Thread/sleep 100)
                         "This will only have a value after 100ms")
               before-time (System/currentTimeMillis)
               ret (wait pc)
               after-time (System/currentTimeMillis)]
           (is (>= (- after-time before-time) 100))
           (is (= "This will only have a value after 100ms" ret))))

  (testa "Should not be used inside an async block, since it will block instead
of parking."
         (async (q! (wait (async "Don't do this even though it works."))))
         (is (= "Don't do this even though it works." (dq!))))

  (testa "Works on values as well, will just return it immediately."
         (is (= :a-value (wait :a-value)))
         (is (= 1 (wait 1)))
         (is (= ["a" "b"] (wait ["a" "b"])))
         (let [before-time (System/currentTimeMillis)
               _ (wait :a-value)
               after-time (System/currentTimeMillis)]
           (is (<= (- after-time before-time) 1))))

  (testa "If an exception is returned by chan, it will re-throw the exception."
         (is (thrown? ArithmeticException (wait (async (/ 1 0))))))

  (testa "Taken value will be fully joined. That means if the value taken is
itself a chan, wait will also take from it, until it eventually takes a non chan
value."
         (is (= 1 (wait (async (async (async 1))))))))


(deftest async-tests
  (testa "Async is used to run some code asynchronously on the async-pool."
         (-> (async (+ 1 2))
             (handle q!))
         (is (= 3 (dq!))))

  (testa "It wraps any form, so even a single value works."
         (-> (async 1)
             (handle q!))
         (is (= 1 (dq!))))

  (testa "You can use it to create async functions, by wrapping the fn body with it."
         (-> ((fn fetch-data [] (async :data)))
             (handle q!))
         (is (= :data (dq!))))

  (testa "The function can return anything."
         (-> ((fn fetch-data [] (async (map inc [1 2 3]))))
             (handle q!))
         (is (= [2 3 4] (dq!))))

  (testa "You can freely throw inside async, the exception will be returned as a value."
         (-> (async (throw (ex-info "Error fetching data" {})))
             (handle q!))
         (is (= "Error fetching data" (ex-message (dq!)))))

  (testa "This works for async functions as well."
         (-> ((fn fetch-data [] (async (throw (ex-info "Error fetching data" {})))))
             (handle q!))
         (is (= "Error fetching data" (ex-message (dq!)))))

  (testa "Async returns a promise-chan immediately, and does not block."
         (let [t1 (System/currentTimeMillis)
               chan (async (Thread/sleep 1000) :done)
               t2 (System/currentTimeMillis)]
           (is (= ManyToManyChannel (type chan)))
           (is (< (- t2 t1) 100))
           (handle chan q!)
           (is (= :done (dq! 2000)))))

  (testa "Async should be used to shuffle data around, or for very small computations.
Because all blocks will run in a fixed size thread pool, which by default has only 8
threads. This means you can't have more than 8 concurrent active blocks, others will
get queued up and have to wait for one of those 8 to finish. That means avoid doing
blocking operations or long running computations, for which you should use blocking
or compute instead."
         (-> (for [i (range 10)]
               (async (Thread/sleep 1000) i))
             (all)
             (handle q!))
         (is (= :timeout (dq! 1100))))

  (testa "Async supports implicit-try, meaning you can catch/finally on it as you would
with try/catch/finally. It's similar to if you always wrapped the inside in a try."
         (-> (async (/ 1 0)
                    (catch ArithmeticException e
                      0))
             (handle q!))
         (is (= 0 (dq!))))

  (testa "Async example of implicit-try with a finally."
         (-> (async (+ 1 1)
                    (finally (q! :finally-called)))
             (handle q!))
         (is (= :finally-called (dq!)))
         (is (= 2 (dq!))))

  (testa "Or with catch and finally together."
         (-> (async (/ 1 0)
                    (catch ArithmeticException e
                      (q! :catch-called)
                      0)
                    (finally (q! :finally-called)))
             (handle q!))
         (is (= :catch-called (dq!)))
         (is (= :finally-called (dq!)))
         (is (= 0 (dq!))))

  (testa "Async block can be cancelled, they will skip their execution if cancelled
before they begin executing."
         (cancel (async (q! :will-timeout-due-to-cancel)))
         (is (= :timeout (dq!))))

  (testa "If you plan on doing lots of work, and want to support it being cancellable,
you can explictly check for cancellation to interupt your work. By default a
cancelled async block returns a CancellationException."
         (let [work (async (loop [i 0]
                             (when-not (cancelled?)
                               (recur (inc i)))))]
           (Thread/sleep 5)
           (cancel work)
           (handle work q!))
         (is (= CancellationException (type (dq! 50)))))

  (testa "You can explicitly set a value when cancelling, and the cancelled async
chan will return a (reduced value) of the value instead of throwing
a CancellationException."
         (let [work (async (loop [i 0]
                             (when-not (cancelled?)
                               (recur (inc i)))))]
           (Thread/sleep 5)
           (cancel work :had-to-cancel)
           (handle work q!))
         (is (= :had-to-cancel (unreduced (dq!))))))


(deftest blocking-tests
  (testa "Blocking is used to run some blocking code asynchronously on the blocking-pool."
         (-> (blocking (+ 1 2))
             (handle q!))
         (is (= 3 (dq!))))

  (testa "It wraps any form, so even a single value works."
         (-> (blocking 1)
             (handle q!))
         (is (= 1 (dq!))))

  (testa "You can use it to create blocking functions, by wrapping the fn body with it."
         (-> ((fn fetch-data [] (blocking :data)))
             (handle q!))
         (is (= :data (dq!))))

  (testa "The function can return anything."
         (-> ((fn fetch-data [] (blocking (map inc [1 2 3]))))
             (handle q!))
         (is (= [2 3 4] (dq!))))

  (testa "You can freely throw inside blocking, the exception will be returned as a value."
         (-> (blocking (throw (ex-info "Error fetching data" {})))
             (handle q!))
         (is (= "Error fetching data" (ex-message (dq!)))))

  (testa "This works for blocking functions as well."
         (-> ((fn fetch-data [] (blocking (throw (ex-info "Error fetching data" {})))))
             (handle q!))
         (is (= "Error fetching data" (ex-message (dq!)))))

  (testa "Blocking returns a promise-chan immediately, and does not block."
         (let [t1 (System/currentTimeMillis)
               chan (blocking (Thread/sleep 1000) :done)
               t2 (System/currentTimeMillis)]
           (is (= ManyToManyChannel (type chan)))
           (is (< (- t2 t1) 100))
           (handle chan q!)
           (is (= :done (dq! 2000)))))

  (testa "Blocking should be used when what you do inside the block is going to
block the running thread. Because unlike async and compute, which both run on
shared thread pool with a limited number of threads, blocking creates as many
threads as are needed so all your blocking blocks run concurrently."
         (-> (for [i (range 200)]
               (blocking (Thread/sleep 1000) i))
             (all)
             (handle q!))
         (is (= (range 200) (dq! 1100))))

  (testa "Blocking supports implicit-try, meaning you can catch/finally on it as you would
with try/catch/finally. It's similar to if you always wrapped the inside in a try."
         (-> (blocking (/ 1 0)
                       (catch ArithmeticException e
                         0))
             (handle q!))
         (is (= 0 (dq!))))

  (testa "Blocking example of implicit-try with a finally."
         (-> (blocking (+ 1 1)
                       (finally (q! :finally-called)))
             (handle q!))
         (is (= :finally-called (dq!)))
         (is (= 2 (dq!))))

  (testa "Or with catch and finally together."
         (-> (blocking (/ 1 0)
                       (catch ArithmeticException e
                         (q! :catch-called)
                         0)
                       (finally (q! :finally-called)))
             (handle q!))
         (is (= :catch-called (dq!)))
         (is (= :finally-called (dq!)))
         (is (= 0 (dq!))))

  (testa "Blocking block can be cancelled, they will skip their execution if cancelled
before they begin executing."
         (cancel (blocking (q! :will-timeout-due-to-cancel)))
         (is (= :timeout (dq!))))

  (testa "If you plan on doing lots of work, and want to support it being cancellable,
you can explictly check for cancellation to interupt your work. By default a
cancelled blocking block returns a CancellationException."
         (let [work (blocking (loop [i 0]
                                (when-not (cancelled?)
                                  (recur (inc i)))))]
           (Thread/sleep 5)
           (cancel work)
           (handle work q!))
         (is (= CancellationException (type (dq! 50)))))

  (testa "You can explicitly set a value when cancelling, and the cancelled blocking
chan will return a (reduced value) of the value instead of throwing
a CancellationException."
         (let [work (blocking (loop [i 0]
                                (when-not (cancelled?)
                                  (recur (inc i)))))]
           (Thread/sleep 5)
           (cancel work :had-to-cancel)
           (handle work q!))
         (is (= :had-to-cancel (unreduced (dq!))))))


(deftest compute-tests
  (testa "Compute is used to run some code asynchronously on the compute-pool."
         (-> (compute (+ 1 2))
             (handle q!))
         (is (= 3 (dq!))))

  (testa "It wraps any form, so even a single value works."
         (-> (compute 1)
             (handle q!))
         (is (= 1 (dq!))))

  (testa "You can use it to create compute functions, by wrapping the fn body with it."
         (-> ((fn fetch-data [] (compute :data)))
             (handle q!))
         (is (= :data (dq!))))

  (testa "The function can return anything."
         (-> ((fn fetch-data [] (compute (map inc [1 2 3]))))
             (handle q!))
         (is (= [2 3 4] (dq!))))

  (testa "You can freely throw inside compute, the exception will be returned as a value."
         (-> (compute (throw (ex-info "Error fetching data" {})))
             (handle q!))
         (is (= "Error fetching data" (ex-message (dq!)))))

  (testa "This works for compute functions as well."
         (-> ((fn fetch-data [] (compute (throw (ex-info "Error fetching data" {})))))
             (handle q!))
         (is (= "Error fetching data" (ex-message (dq!)))))

  (testa "Compute returns a promise-chan immediately, and does not block."
         (let [t1 (System/currentTimeMillis)
               chan (compute (Thread/sleep 1000) :done)
               t2 (System/currentTimeMillis)]
           (is (= ManyToManyChannel (type chan)))
           (is (< (- t2 t1) 100))
           (handle chan q!)
           (is (= :done (dq! 2000)))))

  (testa "Compute should be used for long computations. Because all blocks will
run in a fixed size thread pool, which by default is the number of cores on your
computer + 2. This means you can't have more concurrent active blocks, others will
get queued up and have to wait for one of those to finish. When doing long computations,
"
         (-> (for [i (->> (-> (Runtime/getRuntime) .availableProcessors)
                          (+ 2)
                          inc
                          range)]
               (compute (Thread/sleep 1000) i))
             (all)
             (handle q!))
         (is (= :timeout (dq! 1100))))

  (testa "Compute supports implicit-try, meaning you can catch/finally on it as you would
with try/catch/finally. It's similar to if you always wrapped the inside in a try."
         (-> (compute (/ 1 0)
                      (catch ArithmeticException e
                        0))
             (handle q!))
         (is (= 0 (dq!))))

  (testa "Compute example of implicit-try with a finally."
         (-> (compute (+ 1 1)
                      (finally (q! :finally-called)))
             (handle q!))
         (is (= :finally-called (dq!)))
         (is (= 2 (dq!))))

  (testa "Or with catch and finally together."
         (-> (compute (/ 1 0)
                      (catch ArithmeticException e
                        (q! :catch-called)
                        0)
                      (finally (q! :finally-called)))
             (handle q!))
         (is (= :catch-called (dq!)))
         (is (= :finally-called (dq!)))
         (is (= 0 (dq!))))

  (testa "Compute block can be cancelled, they will skip their execution if cancelled
before they begin executing."
         (cancel (compute (q! :will-timeout-due-to-cancel)))
         (is (= :timeout (dq!))))

  (testa "If you plan on doing lots of work, and want to support it being cancellable,
you can explictly check for cancellation to interupt your work. By default a
cancelled compute block returns a CancellationException."
         (let [work (compute (loop [i 0]
                               (when-not (cancelled?)
                                 (recur (inc i)))))]
           (Thread/sleep 5)
           (cancel work)
           (handle work q!))
         (is (= CancellationException (type (dq! 50)))))

  (testa "You can explicitly set a value when cancelling, and the cancelled compute
chan will return a (reduced value) of the value instead of throwing
a CancellationException."
         (let [work (compute (loop [i 0]
                               (when-not (cancelled?)
                                 (recur (inc i)))))]
           (Thread/sleep 5)
           (cancel work :had-to-cancel)
           (handle work q!))
         (is (= :had-to-cancel (unreduced (dq!))))))


(deftest await-tests
  (testa "Await is used to wait on the result of an async operation."
         (async
           (-> (async (+ 1 2))
               (await)
               (q!)))
         (is (= 3 (dq!))))

  (testa "You can only use await inside an async block, or it'll error."
         (is (thrown? AssertionError (await (async (+ 1 2))))))

  (testa "You can await into a let to wait for the result of some async operation."
         (async
           (let [res (await (async (+ 1 2)))]
             (q! res)))
         (is (= 3 (dq!))))

  (testa "You cannot await into a def, this is a current limitation, core.async suffers
from the same, you can't <! into a def."
         (async
           (def res (await (async (+ 3 5))))
           (catch AssertionError e
             (q! e)))
         (is (= AssertionError (type (dq!)))))

  (testa "Work around this by using an intermediate let."
         (async
           (let [v (await (async (+ 1 2)))]
             (def res v)
             (q! :wait-for-test)))
         (dq!)
         (is (= res 3)))

  (testa "Await waits for async results sequentially."
         (async
           (let [fetch-data (fn [id] (async (str "data-" id)))
                 data1 (await (fetch-data 1))
                 data2 (await (fetch-data 2))]
             (q! data1)
             (q! data2)))
         (is (= "data-1" (dq!)))
         (is (= "data-2" (dq!))))

  (testa "You cannot await inside a letfn, this is a current limitation, which is an
issue with the underlying core.async lib.
See: https://ask.clojure.org/index.php/350/go-ignores-async-code-in-letfn-body"
         (async
           (letfn [(fetch-data [id] (async (str "data-" id)))]
             (await (fetch-data 1)))
           (catch AssertionError e
             (q! e)))
         (is (= AssertionError (type (dq!)))))

  (testa "Work around this by using let instead."
         (async
           (let [fetch-data (fn [id] (async (str "data-" id)))]
             (q! (await (fetch-data 1)))))
         (is (= "data-1" (dq!))))

  (testa "Await re-throws exceptions that have thrown inside the async, and can be
caught using normal try/catch."
         (async
           (let [fetch-data (fn [] (async (throw (ex-info "Error fetching data" {}))))]
             (try (await (fetch-data))
                  (catch Exception e
                    (q! e)))))
         (is (= "Error fetching data" (ex-message (dq!)))))

  (testa "Await takes a chan or a value as first argument, if given a value, it
simply returns it immediately."
         (async
           (q! (await :not-a-chan)))
         (is (= :not-a-chan (dq!))))

  (testa "This goes for forms that return values as well."
         (async
           (q! (await (+ 1 2))))
         (is (= 3 (dq!))))

  (testa "Await supports implicit-try syntax as well, meaning it can be used as a
try/catch/finally, so you don't have to wrap it in a try to handle thrown
exceptions from the async block."
         (async
           (let [fetch-data (fn [] (async (throw (ex-info "Error fetching data" {}))))]
             (await (fetch-data)
               (catch Exception e
                 (q! e))
               (finally (q! :finally-called)))))
         (is (= "Error fetching data" (ex-message (dq!))))
         (is (= :finally-called (dq!))))

  (testa "You cannot await accross function boundary. Same as core.async."
         (-> (async ((fn [] (await (async (+ 1 1))))))
             (handle q!))
         (is (= AssertionError (type (dq!)))))

  (testa "In that case, you need the inner functions to also be async."
         (-> (async ((fn [] (async (await (async (+ 1 1)))))))
             (handle q!))
         (is (= 2 (dq!))))

  (testa "This means the following also does not work, same as core.async, because
for internally creates inner functions."
         (-> (async (doall
                     (for [x [(async 1) (async 2) (async 3)]]
                       (inc (await x)))))
             (handle q!))
         (is (= AssertionError (type (dq!)))))

  (testa "But since we're working in an async-style, we can wrap the inner logic in
async blocks again."
         (-> (doall
              (for [x [(async 1) (async 2) (async 3)]]
                (async (inc (await x)))))
             (all-settled)
             (handle q!))
         (is (= [2 3 4] (dq!))))

  (testa "Or, like in core.async, try to use an alternate construct that doesn't wrap
things in inner functions."
         (-> (async (loop [[x & xs] [(async 1) (async 2) (async 3)] res []]
                      (if x
                        (recur xs (conj res (inc (await x))))
                        res)))
             (handle q!))
         (is (= [2 3 4] (dq!))))

  (testa "Or, await all async operations in the sequence first, and then increment."
         (-> (async (doall
                     (for [x (await (all-settled [(async 1) (async 2) (async 3)]))]
                       (inc x))))
             (handle q!))
         (is (= [2 3 4] (dq!))))

  (testa "Or, leverage more of the other async-style functions."
         (-> [(async 1) (async 2) (async 3)]
             (all-settled)
             (handle #(for [x %] (inc x)))
             (handle q!))
         (is (= [2 3 4] (dq!)))))


(deftest catch-tests
  (testa "Catch awaits the async value and returns it, but if it was an error
it calls the provided error handler with the error, and instead return what that
returns."
         (-> (async (/ 1 0))
             (a/catch (fn [_e] 10))
             (handle q!))
         (is (= 10 (dq!))))
  (testa "You can provide a type to filter the error on, it will only call the
error handler if the error is of that type."
         (-> (async (/ 1 0))
             (a/catch ArithmeticException (fn [_e] 10))
             (handle q!)
             (<<!!))
         (-> (async (/ 1 0))
             (a/catch IllegalStateException (fn [_e] 10))
             (handle q!)
             (<<!!))
         (is (= 10 (dq!)))
         (is (not= 10 (dq!))))
  (testa "So you can chain it to handle different type of errors in different
ways."
         (-> (async (/ 1 0))
             (a/catch IllegalStateException (fn [_e] 1))
             (a/catch ArithmeticException (fn [_e] 2))
             (handle q!))
         (is (= 2 (dq!))))
  (testa "You can provide a predicate instead as well, to filter on the error."
         (-> (async (throw (ex-info "An error" {:type :foo})))
             (a/catch (fn [e] (= :foo (-> e ex-data :type))) (fn [_e] 10))
             (handle q!)
             (<<!!))
         (-> (async (throw (ex-info "An error" {:type :foo})))
             (a/catch (fn [e] (= :bar (-> e ex-data :type))) (fn [_e] 10))
             (handle q!)
             (<<!!))
         (is (= 10 (dq!)))
         (is (not= 10 (dq!)))))


(deftest finally-tests
  (testa "Finally runs a function f after a value is fulfilled on the chan for
side-effect."
         (-> (async (+ 1 2))
             (finally (fn [v] (q! (str "We got " v))))
             (handle q!))
         (is (= "We got 3" (dq!)))
         (is (= 3 (dq!))))
  (testa "The function f is called even if it errored."
         (-> (async (/ 1 0))
             (finally (fn [v] (q! v)))
             (handle q!))
         (is (a/error? (dq!)))
         (is (a/error? (dq!))))
  (testa "It's nice to combine with catch"
         (-> (async (/ 1 0))
             (catch (fn [e] :error))
             (finally (fn [v] (q! (str "We got " v))))
             (handle q!))
         (is (= "We got :error" (dq!)))
         (is (= :error (dq!)))))


(deftest then-tests
  (testa "To do something after a value is fulfilled use then"
         (-> (async (+ 1 2))
             (then inc)
             (then -)
             (then q!))
         (is (= -4 (dq!))))
  (testa "It won't be called if an error occurred, instead the error will be
returned and 'then' short-circuits."
         (-> (async (/ 1 0))
             (then inc)
             (handle q!))
         (is (error? (dq!))))
  (testa "Which is nice to combine with catch and finally."
         (-> (async (/ 1 0))
             (then inc)
             (catch (fn [_e] 0))
             (finally (fn [_v] (q! "Compute done")))
             (handle q!))
         (is (= "Compute done" (dq!)))
         (is (= 0 (dq!)))))


(deftest chain-tests
  (testa "If you want to chain operations, as if by 'then', you an use chain
as well, which can be cleaner."
         (-> (async (+ 1 2))
             (chain inc inc #(* 2 %) -)
             (handle q!))
         (is (= -10 (dq!))))
  (testa "Like 'then', it short-circuit on first error and returns the error
 instead."
         (-> (async (+ 1 2))
             (chain inc #(/ % 0) #(* 2 %) -)
             (handle q!))
         (is (error? (dq!))))
  (testa "Which makes it nice to combine with catch and finally."
         (-> (async (+ 1 2))
             (chain inc #(/ % 0) #(* 2 %) -)
             (catch (fn [_e] 0))
             (finally (fn [_v] (q! "Compute done")))
             (handle q!))
         (is (= "Compute done" (dq!)))
         (is (= 0 (dq!)))))


(deftest handle-tests
  (testa "Handle is used to handle the fulfilled chan result no matter if
it succeeded or errored."
         (-> (async (+ 1 2))
             (handle q!))
         (is (= 3 (dq!)))
         (-> (async (/ 1 0))
             (handle q!))
         (is (error? (dq!))))
  (testa "You can pass it two handlers if you want a different handling if
the result is a success versus an error."
         (-> (async (+ 1 2))
             (handle (fn on-ok [v] (inc v))
                     (fn on-error [_e] 0))
             (handle q!))
         (is (= 4 (dq!)))
         (-> (async (/ 1 0))
             (handle (fn on-ok [v] (inc v))
                     (fn on-error [_e] 0))
             (handle q!))
         (is (= 0 (dq!)))))


(deftest sleep-tests
  (testa "Sleep can be used to sleep X millisecond time inside an async process."
         (-> (async (let [curr-time (System/currentTimeMillis)
                          _ (await (sleep 400))
                          end-time (System/currentTimeMillis)]
                      (- end-time curr-time)))
             (handle q!))
         (is (<= 400 (dq!) 500))))


(deftest defer-tests
  (testa "Defer can be used to schedule something in the future."
         (defer 400 #(q! :done))
         (is (= :timeout (dq! 300)))
         (is (= :done (dq!))))
  (testa "Or to delay returning a value"
         (-> (defer 400 :done)
             (handle q!))
         (is (= :timeout (dq! 300)))
         (is (= :done (dq!)))))


(deftest timeout-tests
  (testa "When you want to wait for a chan to fulfill up too some time in
millisecond you use timeout. It returns a TimeoutException on timeout."
         (-> (timeout (sleep 1000) 500)
             (handle q!))
         (is (instance? TimeoutException (dq!))))
  (testa "Or if you prefer you can have it return a value of your choosing on
timeout instead."
         (-> (timeout (sleep 1000) 500 :it-timed-out)
             (handle q!))
         (is (= :it-timed-out (dq!))))
  (testa "Or if you prefer you can have it run and return a function f of your
choosing on timeout instead."
         (-> (timeout (sleep 1000) 500 #(do (q! "It timed out!") :it-timed-out))
             (handle q!))
         (is (= "It timed out!" (dq!)))
         (is (= :it-timed-out (dq!))))
  (testa "When it doesn't time out, it returns the value from the chan."
         (-> (timeout (async (+ 1 2)) 500)
             (handle q!))
         (is (= 3 (dq!))))
  (testa "When it does time out, chan will also be cancelled if possible."
         (-> (timeout
              (blocking (loop [i 0]
                          (when-not (cancelled?)
                            (q! i)
                            (Thread/sleep 100)
                            (recur (inc i)))))
              500
              :it-timed-out)
             (handle q!)
             (wait))
         (let [[x & xs] (loop [xs [] x (dq!)]
                          (if (= x :it-timed-out)
                            (into [x] xs)
                            (recur (conj xs x) (dq!))))]
           (is (> (count xs) 3))
           (is (= :it-timed-out x)))))


(deftest race-tests
  (testa "Race returns the first chan to fulfill."
         (-> (race [(defer 100 1) (defer 100 2) (async 3)])
             (handle q!))
         (is (= 3 (dq!))))

  (testa "If the first chan to fulfill does so with an error?, it still
   wins the race and the error gets returned in the promise-chan."
         (-> (race [(async (throw (ex-info "error" {})))
                    (defer 100 :ok)])
             (handle q!))
         (is (error? (dq!))))

  (testa "Passing an empty seq will return a closed promise-chan, which in
   turn returns nil when taken from."
         (-> (race [])
             (handle q!))
         (is (nil? (dq!))))

  (testa "Passing nil will return a closed promise-chan, which in
   turn returns nil when taken from."
         (-> (race nil)
             (handle q!))
         (is (nil? (dq!))))

  (testa "chans can also contain values which will be treated as an already
   settled chan which returns itself."
         (-> (race [1 (defer 100 2)])
             (handle q!))
         (is (= 1 (dq!))))

  (testa "When passing values, they are still racing asynchronously against
   each other, and the result order is non-deterministic and should
   not be depended on."
         (is (not
              (every?
               #{1}
               (doall
                (for [i (range 3000)]
                  (-> (race [1 2 3 4 5 6 7 8 9 (async 10)])
                      (<<??))))))))

  (testa "Race cancels all other chans once one of them fulfills."
         (-> (race [(blocking
                      (Thread/sleep 100)
                      (when (cancelled?)
                        (q! :cancelled)))
                    (blocking
                      (Thread/sleep 100)
                      (when (cancelled?)
                        (q! :cancelled)))
                    (blocking
                      (Thread/sleep 100)
                      (when (cancelled?)
                        (q! :cancelled)))
                    (async :done)])
             (handle q!))
         (is (= :done (dq!)))
         (is (= :cancelled (dq!)))
         (is (= :cancelled (dq!)))
         (is (= :cancelled (dq!))))

  (testa "Even if the first one to fulfill did so with an error, others are
   still cancelled."
         (-> (race [(blocking
                      (Thread/sleep 100)
                      (when (cancelled?)
                        (q! :cancelled)))
                    (blocking
                      (Thread/sleep 100)
                      (when (cancelled?)
                        (q! :cancelled)))
                    (blocking
                      (Thread/sleep 100)
                      (when (cancelled?)
                        (q! :cancelled)))
                    (async (throw (ex-info "error" {})))])
             (handle q!))
         (is (error? (dq!)))
         (is (= :cancelled (dq!)))
         (is (= :cancelled (dq!)))
         (is (= :cancelled (dq!)))))


(deftest any-tests
  (testa "Any returns the first chan to fulfill with an ok?."
         (-> (any [(async (throw (ex-info "error" {}))) (defer 100 2) (defer 50 3)])
             (handle q!))
         (is (= 3 (dq!))))

  (testa "As we saw, chans that fulfill in error? are ignored, so when all chans
   fulfill in error?, any returns a promise-chan settled with an error of
   its own. This error will contain the list of all errors taken from all
   chans and its ex-data will have a key :type with value :all-errored."
         (-> (any [(async (throw (ex-info "error" {})))
                   (async (throw (ex-info "error" {})))
                   (async (throw (ex-info "error" {})))])
             (handle q!))
         (let [ret (dq!)]
           (is (error? ret))
           (is (= :all-errored (:type (ex-data ret))))))

  (testa "Passing an empty seq will return a closed promise-chan, which in
   turn returns nil when taken from."
         (-> (any [])
             (handle q!))
         (is (nil? (dq!))))

  (testa "Passing nil will return a closed promise-chan, which in
   turn returns nil when taken from."
         (-> (any nil)
             (handle q!))
         (is (nil? (dq!))))

  (testa "chans can also contain values which will be treated as an already
   settled chan which returns itself."
         (-> (any [1 (defer 100 2)])
             (handle q!))
         (is (= 1 (dq!))))

  (testa "When passing values, they are still racing asynchronously against
   each other, and the result order is non-deterministic and should
   not be depended on."
         (is (not
              (every?
               #{1}
               (doall
                (for [i (range 3000)]
                  (-> (any [1 2 3 4 5 6 7 8 9 (async 10)])
                      (<<??))))))))

  (testa "Any cancels all other chans once one of them fulfills in ok?."
         (-> (any [(blocking
                     (Thread/sleep 100)
                     (when (cancelled?)
                       (q! :cancelled)))
                   (blocking
                     (Thread/sleep 100)
                     (when (cancelled?)
                       (q! :cancelled)))
                   (blocking
                     (Thread/sleep 100)
                     (when (cancelled?)
                       (q! :cancelled)))
                   (defer 30 :done)
                   (async (q! :throwed) (throw (ex-info "" {})))])
             (handle q!))
         (is (= :throwed (dq!)))
         (is (= :done (dq!)))
         (is (= :cancelled (dq!)))
         (is (= :cancelled (dq!)))
         (is (= :cancelled (dq!))))

  (testa "If the first one to fulfill did so with an error, others are
   not cancelled. But as soon as any of the other fulfill, the remaining
   ones will cancel."
         (-> (any [(blocking
                     (Thread/sleep 30)
                     (if (cancelled?)
                       (q! :cancelled)
                       (q! :not-cancelled)))
                   (blocking
                     (Thread/sleep 60)
                     (if (cancelled?)
                       (q! :cancelled)
                       (q! :not-cancelled)))
                   (async (throw (ex-info "error" {})))])
             (handle q!))
         (is (= :not-cancelled (dq!)))
         (is (= :not-cancelled (dq!)))
         (is (= :cancelled (dq!)))))


(deftest all-settled-tests
  (testa "all-settled returns a promise-chan settled with a vector of the taken values
   from all chans once they all fulfill"
         (-> (all-settled [(async 1) (async 1) (async 1)])
             (handle q!))
         (is (= [1 1 1] (dq!))))

  (testa "This is true even if any of the chan fulfills with an error."
         (let [error (ex-info "error" {})]
           (-> (all-settled [(async 1) (async (throw error)) (async 1)])
               (handle q!))
           (is (= [1 error 1] (dq!)))))

  (testa "Or even if all of them fulfill with an error."
         (let [error (ex-info "error" {})]
           (-> (all-settled [(async (throw error)) (async (throw error)) (async (throw error))])
               (handle q!))
           (is (= [error error error] (dq!)))))

  (testa "Passing an empty seq will return a promise-chan settled with an
   empty vector."
         (-> (all-settled [])
             (handle q!))
         (is (= [] (dq!))))

  (testa "Same with passing nil, it will return a promise-chan settled with an
   empty vector."
         (-> (all-settled nil)
             (handle q!))
         (is (= [] (dq!))))

  (testa "The returned promise-chan will always contain a vector? even if chans isn't
   one."
         (-> (all-settled (list (async 1) (async 1) (async 1)))
             (handle q!))
         (is (vector? (dq!))))

  (testa "chans can contain values as well as channels, in which case they
   will be treated as an already settled chan which returns itself."
         (-> (all-settled [1 2 (async 1)])
             (handle q!))
         (is (= [1 2 1] (dq!))))

  (testa "The order of the taken values in the vector settled into the returned
   promise-chan are guaranteed to correspond to the same order as the chans.
   Thus order can be relied upon."
         (is
          (every?
           #(= [1 2 3 4 5 6 7 8 9 10] %)
           (doall
            (for [i (range 3000)]
              (-> (all-settled [1 (async 2) 3 (async 4) (async 5) 6 (async 7) 8 9 (async 10)])
                  (<<??))))))))


(deftest all-tests
  (testa "Awaits all promise-chan in given seq and returns a vector of
their results in order. Promise-chan obviously run concurrently,
so the entire operation will take as long as the longest one."
         (async
           (q! (await (all [1 (defer 100 2) (defer 200 3)]))))
         (is (= [1 2 3] (dq! 250))))

  (testa "Short-circuits as soon as one promise-chan errors, returning
the error."
         (async
           (try
             (await (all [(defer 10 #(/ 1 0)) (defer 100 2) (defer 200 3)]))
             (catch Exception e
               (q! e))))
         (is (= ArithmeticException (type (dq! 50)))))

  (testa
    "Can contain non-promise-chan as well."
    (async
      (q! (await (all [1 2 3]))))
    (is (= [1 2 3] (dq!))))

  (testa "But since all is not a macro, if one of them throw it throws
immediately as the argument to all get evaluated."
         (try (all [1 (defer 10 2) (/ 1 0)])
              (catch Exception e
                (q! e)))
         (is (= ArithmeticException (type (dq!)))))

  (testa "This is especially important when using some of the helper
functions instead of plain async/await, because it will
throw instead of all returning the error."
         (try
           (-> (all [1 (defer 10 2) (/ 1 0)])
               (a/catch (q! :this-wont-work)))
           (catch Exception _
             (q! :this-will)))
         (is (= :this-will (dq!))))

  (testa "If given an empty seq, returns a promise-chan settled
to nil."
         (async
           (q! (await (all []))))
         (is (nil? (dq!))))

  (testa "If given nil, returns a promise-chan settled to nil."
         (async
           (q! (await (all []))))
         (is (nil? (dq!)))))


(deftest do!-tests
  (testa "Use do! to perform asynchronous ops one after the other and
return the result of the last one only."
         (-> (do! (blocking (q! :log))
                  (blocking (q! :prep-database))
                  (blocking :run-query))
             (handle q!))
         (is (= :log (dq!)))
         (is (= :prep-database (dq!)))
         (is (= :run-query (dq!)))))


(deftest alet-tests
  (testa "Like Clojure's let, except each binding is awaited."
         (alet
             [a (defer 100 1)
              b (defer 100 2)
              c (defer 100 3)]
           (q! (+ a b c)))
         (is (= 6 (dq!))))

  (testa "Like Clojure's let, this is not parallel, the first binding is
awaited, then the second, then the third, etc."
         (alet
             [a (defer 100 1)
              b (defer 100 2)
              c (defer 100 3)]
           (q! (+ a b c)))
         (is (= :timeout (dq! 200))))

  (testa
    "Let bindings can depend on previous ones."
    (alet
        [a 1
         b (defer 100 (+ a a))
         c (inc b)]
      (q! c))
    (is (= 3 (dq!)))))


(deftest clet-tests
  (testa "Like Clojure's let, but it runs bindings concurrently."
         (time
          (clet
              [a (defer 100 1)
               b (defer 100 2)
               c (defer 100 3)]
            (q! (+ a b c)))
          q!)
         (is (= 6 (dq!)))
         (is (< 100 (dq!) 110)))

  (testa "Unless one binding depends on a previous one, in which case that binding
will await the other, but only the dependent bindings will be sequenced after what
they depend on, others will still run concurrently."
         (time
          (clet
              [a (defer 100 100)
               b (defer a 100)
               c (defer b 100)
               d (defer 100 0)]
            (q! (+ a b c d)))
          q!)
         (is (= 300 (dq!)))
         (is (< 300 (dq!) 320)))

  (testa "Bindings evaluate on the async-pool, which means they should not do
any blocking or heavy compute operation. If you need to do a blocking or compute
heavy operation wrap it in blocking or compute accordingly."
         (time
          (clet
              [a (compute (reduce + (mapv #(Math/sqrt %) (range 1e6))))
               b (blocking (Thread/sleep 100) 100)
               c (defer 100 100)
               d (defer 100 b)]
            (q! (+ a b c d)))
          q!)
         (is (= 6.666664664588418E8 (dq!)))
         (is (< 200 (dq!) 220)))

  (testa "You can nest async inside blocking and compute and so on and it'll still
properly rewrite to await or wait depending on which the binding is inside. This is
not useful, this is just to test that it works."
         (time
          (clet
              [a (compute (reduce + (mapv #(Math/sqrt %) (range 1e6))))
               b (defer 100 100)
               c (blocking (Thread/sleep b) (async b (blocking b)))
               d (defer 100 b)]
            (q! (+ a b c d)))
          q!)
         (is (= 6.666664664588418E8 (dq!)))
         (is (< 200 (dq!) 220)))

  (testa "Testing some edge case, proper rewriting inside the body should also
happen where bindings are replaced by await or wait depending on their context."
         (time
          (clet
              [x 1
               a (compute x)
               b (blocking (inc a))
               c (async (inc b))
               d (compute (async (inc c)))]
            (q! (+ a
                   (await (compute b))
                   (await (async c))
                   (await (blocking d))
                   (await (compute (async a)))
                   (await (blocking (async a)))
                   (await (async (blocking a)))
                   (await (async (compute a)))
                   (await (compute a (async a (blocking a (async a))))))))
          q!)
         (is (= 15 (dq!)))
         (is (< 0 (dq!) 50))
         (is (= '(let*
                     [x (com.xadecimal.async-style.impl/async 1)
                      a (com.xadecimal.async-style.impl/async
                          (compute (com.xadecimal.async-style.impl/wait x)))
                      b (com.xadecimal.async-style.impl/async
                          (blocking (inc (com.xadecimal.async-style.impl/wait a))))
                      c (com.xadecimal.async-style.impl/async
                          (async (inc (com.xadecimal.async-style.impl/await b))))
                      d (com.xadecimal.async-style.impl/async
                          (compute (async (inc (com.xadecimal.async-style.impl/await c)))))
                      e (com.xadecimal.async-style.impl/async
                          (inc (com.xadecimal.async-style.impl/await d)))]
                   (com.xadecimal.async-style.impl/async
                     (+ (com.xadecimal.async-style.impl/await a)
                        (com.xadecimal.async-style.impl/await e)
                        (await (compute (com.xadecimal.async-style.impl/wait b)))
                        (await (async (com.xadecimal.async-style.impl/await c)))
                        (await (blocking (com.xadecimal.async-style.impl/wait d)))
                        (await (compute (async (com.xadecimal.async-style.impl/await a))))
                        (await (blocking (async (com.xadecimal.async-style.impl/await a))))
                        (await (async (blocking (com.xadecimal.async-style.impl/wait a))))
                        (await (async (compute (com.xadecimal.async-style.impl/wait a))))
                        (await (async (compute (com.xadecimal.async-style.impl/wait a))))
                        (await
                            (compute (com.xadecimal.async-style.impl/wait a)
                                     (async (com.xadecimal.async-style.impl/await a)
                                            (blocking (com.xadecimal.async-style.impl/wait a)
                                                      (async (com.xadecimal.async-style.impl/await a)))))))))
                (macroexpand '(com.xadecimal.async-style.impl/clet
                                  [x 1
                                   a (compute x)
                                   b (blocking (inc a))
                                   c (async (inc b))
                                   d (compute (async (inc c)))
                                   e (inc d)]
                                (+ a
                                   e
                                   (await (compute b))
                                   (await (async c))
                                   (await (blocking d))
                                   (await (compute (async a)))
                                   (await (blocking (async a)))
                                   (await (async (blocking a)))
                                   (await (async (compute a)))
                                   (await (async (compute a)))
                                   (await (compute a (async a (blocking a (async a))))))))))))


(deftest time-tests
  (testa "If you want to measure the time an async ops takes to fulfill, you can
wrap it with time. By default it prints to stdout."
         (-> (sleep 1000)
             (time)
             wait))
  (testa "You can pass a custom print-fn as the second argument instead."
         (-> (sleep 1000)
             (time q!)
             wait)
         (is (< 1000 (dq!) 1010)))
  (testa "It can also be used to time non-async ops."
         (-> (+ 1 2 3)
             (time q!))
         (is (< 0 (dq!) 1)))
  (testa "Unlike clojure.core/time, this one returns the value of what it
times and not nil."
         (is (= 3 (time (+ 1 2))))))
