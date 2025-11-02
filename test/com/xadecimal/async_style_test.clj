(ns com.xadecimal.async-style-test
  (:refer-clojure :exclude [await time])
  (:require [clojure.test :refer [deftest is]]
            [com.xadecimal.async-style :as a :refer :all]
            [com.xadecimal.async-style.impl :as impl]
            [com.xadecimal.testa :refer [testa q! dq!]])
  (:import [java.lang AssertionError]
           [java.util.concurrent CancellationException TimeoutException
            CompletableFuture]
           [clojure.core.async.impl.channels ManyToManyChannel]
           [clojure.lang ExceptionInfo]))


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
                      (if (and (pos? i) (not (cancelled?)))
                        (recur (dec i) (+ acc i))
                        acc)))
             (handle q!))
         (is (= 5050 (dq!))))

  (testa "Here it is cancelling your loop."
         (let [result (atom 0)
               promise-chan (async (loop [i 100 acc 0]
                                     (reset! result acc)
                                     (Thread/sleep 1)
                                     (if (and (pos? i) (not (cancelled?)))
                                       (recur (dec i) (+ acc i))
                                       acc)))]
           (handle promise-chan q!)
           (Thread/sleep 30)
           (cancel! promise-chan)
           (is (instance? CancellationException (dq!)))
           (is (> 5050 @result))))

  (testa "When used outside an async block, it returns false."
         (is (= false (cancelled?))))

  (testa "Or the value of the thread's interrupt flag."
         (let [f (future (.interrupt (Thread/currentThread))
                         (cancelled?))]
           (is (= true @f))))

  (testa "Returns true if cancelled."
         (let [promise-chan (async (Thread/sleep 60)
                                   (q! (cancelled?)))]
           (Thread/sleep 30)
           (cancel! promise-chan))
         (is (dq!)))

  (testa "False otherwise."
         (async (q! (cancelled?)))
         (is (not (dq!)))))


(deftest check-cancelled!-tests
  (testa "Async-style supports cancellation, you have to explicitly check for
it using check-cancelled! inside your async blocks."
         (-> (async (loop [i 100 acc 0]
                      (check-cancelled!)
                      (if (pos? i)
                        (recur (dec i) (+ acc i))
                        acc)))
             (handle q!))
         (is (= 5050 (dq!))))

  (testa "Here it is cancelling your loop."
         (let [result (atom 0)
               promise-chan (async (loop [i 100 acc 0]
                                     (check-cancelled!)
                                     (reset! result acc)
                                     (Thread/sleep 1)
                                     (if (pos? i)
                                       (recur (dec i) (+ acc i))
                                       acc)))]
           (handle promise-chan q!)
           (Thread/sleep 30)
           (cancel! promise-chan)
           (is (instance? CancellationException (dq!)))
           (is (> 5050 @result))))

  (testa "When used outside an async block, it returns nil."
         (is (= nil (check-cancelled!))))

  (testa "Or if the thread interrupt flag is set it throws InterruptedException."
         (let [f (future (.interrupt (Thread/currentThread))
                         (check-cancelled!))]
           (is (instance? InterruptedException (try @f
                                                    (catch Exception e
                                                      (.getCause e)))))))

  (testa "Throws InterruptedException if cancelled."
         (let [promise-chan (async (Thread/sleep 60)
                                   (check-cancelled!)
                                   (catch InterruptedException e
                                     (q! e)))]
           (Thread/sleep 30)
           (cancel! promise-chan))
         (is (instance? InterruptedException (dq!))))

  (testa "Returns nil and doesn't throw otherwise."
         (async (q! (check-cancelled!))
                (catch CancellationException e
                  (q! e)))
         (is (nil? (dq!)))))


(deftest cancel!-tests
  (testa "You can use cancel! to cancel an async block, this is best effort.
If the block has not started executing yet, it will cancel, otherwise it
needs to be the async block explicitly checks for cancelled? at certain
points in time and short-circuit/interrupt, or cancel! will not be able to
actually cancel."
         (let [promise-chan (async "I am cancelled")]
           (cancel! promise-chan)
           (is (= CancellationException (type (wait* promise-chan))))))

  (testa "If the block has started executing, it won't cancel without explicit
cancellation? check."
         (let [promise-chan (async "I am not cancelled")]
           (Thread/sleep 30)
           (cancel! promise-chan)
           (is (= "I am not cancelled" (wait* promise-chan)))))

  (testa "If the block checks for cancellation explicitly, it can still be
cancelled."
         (let [promise-chan (async (Thread/sleep 100)
                                   "I am cancelled")]
           (Thread/sleep 30)
           (cancel! promise-chan)
           (is (= CancellationException (type (wait* promise-chan))))))

  (testa "A value can be specified when cancelling, which is returned by the
promise-chan of the async block instead of returning a CancellationException."
         (let [promise-chan (async (Thread/sleep 100)
                                   "I am cancelled")]
           (Thread/sleep 30)
           (cancel! promise-chan "We had to cancel this.")
           (is (= "We had to cancel this." (wait* promise-chan)))))

  (testa "Any value can be set as the cancelled val."
         (let [promise-chan (blocking (Thread/sleep 100) "Not cancelled")]
           (cancel! promise-chan 123)
           (is (= 123 (wait promise-chan))))
         (let [promise-chan (blocking (Thread/sleep 100) "Not cancelled")]
           (cancel! promise-chan :foo)
           (is (= :foo (wait promise-chan))))
         (let [promise-chan (blocking (Thread/sleep 100) "Not cancelled")]
           (cancel! promise-chan "cool")
           (is (= "cool" (wait promise-chan))))
         (let [promise-chan (blocking (Thread/sleep 100) "Not cancelled")]
           (cancel! promise-chan {:complex true})
           (is (= {:complex true} (wait promise-chan))))
         (let [promise-chan (blocking (Thread/sleep 100) "Not cancelled")]
           (cancel! promise-chan (ex-info "Even exceptions" {:error true}))
           (is (thrown? ExceptionInfo (wait promise-chan)))))

  (testa "Except for nil, since nil is ambiguous between has the chan been
cancelled or is there just nothing to poll!, so if you try to cancel! with nil
it throws."
         (let [promise-chan (blocking (Thread/sleep 100) "Not cancelled")]
           (is (thrown-with-msg? IllegalArgumentException #"Can't put nil .*"
                                 (cancel! promise-chan nil)))
           (is (= "Not cancelled" (wait promise-chan)))))

  (testa "So just like in core.async, you need to use something else to represent
nil in that case, like :nil or (reduced nil)"
         (let [promise-chan (blocking (Thread/sleep 100) "Not cancelled")]
           (cancel! promise-chan :nil)
           (is (= :nil (wait promise-chan))))
         (let [promise-chan (blocking (Thread/sleep 100) "Not cancelled")]
           (cancel! promise-chan (reduced nil))
           (is (nil? @(wait promise-chan))))))


(deftest await*-tests
  (testa "Takes a value from a chan."
         (async
           (q! (await* (async "Hello!"))))
         (is (= "Hello!" (dq!))))

  (testa "Parks if none are available yet, resumes only once a value is available."
         (async
           (let [pc (async (Thread/sleep 100)
                           "This will only have a value after 100ms")]
             (q! (System/currentTimeMillis))
             (q! (await* pc))
             (q! (System/currentTimeMillis))))
         (let [before-time (dq!)
               ret (dq!)
               after-time (dq!)]
           (is (>= (- after-time before-time) 100))
           (is (= "This will only have a value after 100ms" ret))))

  (testa "Throws if used outside an async block"
         (is (thrown? AssertionError (await* (async)))))

  (testa "Works on values as well, will just return it immediately."
         (async (q! (await* :a-value))
                (q! (await* 1))
                (q! (await* ["a" "b"]))
                (q! (System/currentTimeMillis))
                (q! (await* :a-value))
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
           (q! (await* (async (/ 1 0)))))
         (is (= ArithmeticException (type (dq!)))))

  (testa "Taken value will be fully joined. That means if the value taken is
itself a chan, <<! will also take from it, until it eventually takes a non chan
value."
         (async
           (q! (await* (async (async (async 1))))))
         (is (= 1 (dq!)))))


(deftest wait*-tests
  (testa "Takes a value from a chan."
         (is (= "Hello!" (wait* (async "Hello!")))))

  (testa "Blocks if none are available yet, resumes only once a value is available."
         (let [pc (async (Thread/sleep 100)
                         "This will only have a value after 100ms")
               before-time (System/currentTimeMillis)
               ret (wait* pc)
               after-time (System/currentTimeMillis)]
           (is (>= (- after-time before-time) 100))
           (is (= "This will only have a value after 100ms" ret))))

  (testa "Should not be used inside an async block, since it will block instead
of parking."
         (async (q! (wait* (async "Don't do this even though it works."))))
         (is (= "Don't do this even though it works." (dq!))))

  (testa "Works on values as well, will just return it immediately."
         (is (= :a-value (wait* :a-value)))
         (is (= 1 (wait* 1)))
         (is (= ["a" "b"] (wait* ["a" "b"])))
         (let [before-time (System/currentTimeMillis)
               _ (wait* :a-value)
               after-time (System/currentTimeMillis)]
           (is (<= (- after-time before-time) 1))))

  (testa "If an exception is returned by chan, it will return the exception,
it won't throw."
         (is (= ArithmeticException (type (wait* (async (/ 1 0)))))))

  (testa "Taken value will be fully joined. That means if the value taken is
itself a chan, <<!! will also take from it, until it eventually takes a non chan
value."
         (is (= 1 (wait* (async (async (async 1))))))))


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
         (is (= 1 (wait (async (async (async 1)))))))

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


(deftest ->promise-chan-tests
  (testa "Given a promise-chan, returns it."
         (let [pc (async 0)]
           (is (= pc (->promise-chan pc)))))
  (testa "Given a future, returns a promise-chan of it"
         (let [f (future 0)]
           (is (#'impl/chan? (->promise-chan f)))))
  (testa "Given a CompletableFuture, returns a promise-chan of it"
         (let [f (CompletableFuture/completedFuture 0)]
           (is (#'impl/chan? (->promise-chan f)))))
  (testa "Given a IBlockingDeref, returns a promise-chan of it"
         (let [f (promise)]
           (is (#'impl/chan? (->promise-chan f)))))
  (testa "Given a non-coercable to promise-chan, returns it."
         (let [x nil]
           (is (= x (->promise-chan x))))
         (let [x 1]
           (is (= x (->promise-chan x))))
         (let [x (atom 0)]
           (is (= x (->promise-chan x))))
         (let [x "abc"]
           (is (= x (->promise-chan x)))))
  (testa "When a future is coerced, it still supports cancellation."
         (let [pc (->promise-chan (future (Thread/sleep 500) (q! "baz") 123))]
           (Thread/sleep 100)
           (cancel! pc :cancelled)
           (is (= :cancelled (wait pc)))
           (is (= :timeout (dq!)))))
  (testa "When a CompletableFuture is coerced, it still supports cancellation.
CompletableFuture can't interrupt themselves, but will be marked as cancelled."
         (let [cf (CompletableFuture/supplyAsync
                   (bound-fn [] (Thread/sleep 500) (q! "baz") 123))
               pc (->promise-chan cf)]
           (Thread/sleep 100)
           (cancel! pc :cancelled)
           (is (= :cancelled (wait pc)))
           (is (="baz" (dq!)))
           (is (true? (.isCancelled cf)))))
  (testa "If an error occurs inside the future, IBlockingDeref or CompletableFuture
it is properly coerced as a standard async-style error. We unwrap ExecutionException
from Future and CompletableFuture so you get the same exception as if you'd use
async-style to execute the task"
         (is (thrown? ArithmeticException (wait (future (/ 1 0)))))
         (is (thrown? ArithmeticException (wait (-> (promise) (deliver (/ 1 0))))))
         (is (thrown? ArithmeticException (wait (CompletableFuture/supplyAsync
                                                 (bound-fn [] (/ 1 0)))))))
  (testa "Nested combinations of future, IBlockingDeref, CompletableFuture
and PromiseChan all unwrap and join fully."
         (is (= 1 (wait (future (future 1)))))
         (is (= 1 (wait (-> (promise) (deliver (-> (promise) (deliver 1)))))))
         (is (= 1 (wait (CompletableFuture/supplyAsync
                         (bound-fn [] (CompletableFuture/supplyAsync
                                       (bound-fn [] 1)))))))
         (is (= 1 (wait (async (future 1)))))
         (is (= 1 (wait (-> (promise) (deliver (compute 1))))))
         (is (= 1 (wait (async (CompletableFuture/supplyAsync
                                (bound-fn [] 1))))))
         (is (= 1 (wait (future
                          (-> (promise)
                              (deliver
                               (CompletableFuture/supplyAsync
                                (bound-fn []
                                  (-> (promise)
                                      (deliver
                                       (future
                                         (CompletableFuture/supplyAsync
                                          (bound-fn [] 1))))))))))))))
  (testa "Even with wait*"
         (is (= 1 (wait* (future (future 1)))))
         (is (= 1 (wait* (-> (promise) (deliver (-> (promise) (deliver 1)))))))
         (is (= 1 (wait* (CompletableFuture/supplyAsync
                          (bound-fn [] (CompletableFuture/supplyAsync
                                        (bound-fn [] 1)))))))
         (is (= 1 (wait* (async (future 1)))))
         (is (= 1 (wait* (-> (promise) (deliver (compute 1))))))
         (is (= 1 (wait* (async (CompletableFuture/supplyAsync
                                 (bound-fn [] 1))))))
         (is (= 1 (wait* (future
                           (-> (promise)
                               (deliver
                                (CompletableFuture/supplyAsync
                                 (bound-fn []
                                   (-> (promise)
                                       (deliver
                                        (future
                                          (CompletableFuture/supplyAsync
                                           (bound-fn [] 1))))))))))))))
  (testa "Even with await"
         (async
           (q! (await (future (future 1))))
           (q! (await (-> (promise) (deliver (-> (promise) (deliver 1))))))
           (q! (await (CompletableFuture/supplyAsync
                       (bound-fn [] (CompletableFuture/supplyAsync
                                     (bound-fn [] 1))))))
           (q! (await (async (future 1))))
           (q! (await (-> (promise) (deliver (compute 1)))))
           (q! (await (async (CompletableFuture/supplyAsync
                              (bound-fn [] 1)))))
           (q! (await (future
                        (-> (promise)
                            (deliver
                             (CompletableFuture/supplyAsync
                              (bound-fn []
                                (-> (promise)
                                    (deliver
                                     (future
                                       (CompletableFuture/supplyAsync
                                        (bound-fn [] 1)))))))))))))
         (dotimes [_i 7]
           (is (= 1 (dq!)))))
  (testa "Even with await*"
         (async
           (q! (await* (future (future 1))))
           (q! (await* (-> (promise) (deliver (-> (promise) (deliver 1))))))
           (q! (await* (CompletableFuture/supplyAsync
                        (bound-fn [] (CompletableFuture/supplyAsync
                                      (bound-fn [] 1))))))
           (q! (await* (async (future 1))))
           (q! (await* (-> (promise) (deliver (compute 1)))))
           (q! (await* (async (CompletableFuture/supplyAsync
                               (bound-fn [] 1)))))
           (q! (await* (future
                         (-> (promise)
                             (deliver
                              (CompletableFuture/supplyAsync
                               (bound-fn []
                                 (-> (promise)
                                     (deliver
                                      (future
                                        (CompletableFuture/supplyAsync
                                         (bound-fn [] 1)))))))))))))
         (dotimes [_i 7]
           (is (= 1 (dq!)))))
  (testa "Even for errors"
         (is (thrown? ArithmeticException
                      (wait (future (future (/ 1 0))))))
         (is (thrown? ArithmeticException
                      (wait (-> (promise) (deliver (-> (promise) (deliver (/ 1 0))))))))
         (is (thrown? ArithmeticException
                      (wait (CompletableFuture/supplyAsync
                             (bound-fn [] (CompletableFuture/supplyAsync
                                           (bound-fn [] (/ 1 0))))))))
         (is (thrown? ArithmeticException
                      (wait (async (future (/ 1 0))))))
         (is (thrown? ArithmeticException
                      (wait (-> (promise) (deliver (compute (/ 1 0)))))))
         (is (thrown? ArithmeticException
                      (wait (async (CompletableFuture/supplyAsync
                                    (bound-fn [] (/ 1 0)))))))
         (is (thrown? ArithmeticException
                      (wait (future
                              (-> (promise)
                                  (deliver
                                   (CompletableFuture/supplyAsync
                                    (bound-fn []
                                      (-> (promise)
                                          (deliver
                                           (future
                                             (CompletableFuture/supplyAsync
                                              (bound-fn [] (/ 1 0)))))))))))))))
  (testa "Waiting for future, IBlockingDeref or CompletableFuture works as
expected."
         (is (= 1 (wait (CompletableFuture/supplyAsync
                         (bound-fn [] (Thread/sleep 250) 1)))))
         (is (= 1 (wait* (CompletableFuture/supplyAsync
                          (bound-fn [] (Thread/sleep 250) 1)))))
         (async (q! (await (CompletableFuture/supplyAsync
                            (bound-fn [] (Thread/sleep 250) 1))))
                (q! (await* (CompletableFuture/supplyAsync
                             (bound-fn [] (Thread/sleep 250) 1)))))
         (is (= 1 (dq!)))
         (is (= 1 (dq!))))
  (testa "If a future or CompletableFuture is cancelled, it's cancellation
exception is thrown"
         (let [f (CompletableFuture/supplyAsync
                  (bound-fn [] (Thread/sleep 250) 1))]
           (.cancel f true)
           (is (thrown? CancellationException (wait f))))
         (let [f (future (Thread/sleep 250) 1)]
           (future-cancel f)
           (is (thrown? CancellationException (wait f))))))


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
or compute instead. In core.async 1.8+ this is no longer true, as the async pool
has been replaced by an unbounded pool, but it is still a good practice to use
it only for async control flow."
         (-> (for [i (range 10)]
               (async (Thread/sleep 1000) i))
             (all)
             (handle q!))
         (if @#'impl/executor-for
           (is (= [0 1 2 3 4 5 6 7 8 9] (dq! 1100)))
           (is (= :timeout (dq! 1100)))))

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
         (cancel! (async (q! :will-timeout-due-to-cancel)))
         (is (= :timeout (dq!))))

  (testa "If you plan on doing lots of work, and want to support it being cancellable,
you can explictly check for cancellation to interupt your work. By default a
cancelled async block returns a CancellationException."
         (let [work (async (loop [i 0]
                             (when-not (cancelled?)
                               (recur (inc i)))))]
           (Thread/sleep 5)
           (cancel! work)
           (handle work q!))
         (is (= CancellationException (type (dq! 50)))))

  (testa "You can explicitly set a value when cancelling, and the cancelled async
chan will return that value instead of throwing a CancellationException."
         (let [work (async (loop [i 0]
                             (when-not (cancelled?)
                               (recur (inc i)))))]
           (Thread/sleep 5)
           (cancel! work :had-to-cancel)
           (handle work q!))
         (is (= :had-to-cancel (dq!))))

  (testa "Java platform level cancellation is not supported in async, and
therefore, if you block inside it, which you are not supposed to do, you won't
be able to cancel those blocking ops."
         (let [task (async (Thread/sleep 500)
                           (println "This will print")
                           (q! :was-cancelled))]
           (Thread/sleep 100)
           (cancel! task :cancelled)
           (q! (wait task))
           (is (= :cancelled (dq!)))
           (is (= :was-cancelled (dq!))))))


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
         (cancel! (blocking (Thread/sleep 1) (q! :will-timeout-due-to-cancel)))
         (is (= :timeout (dq!))))

  (testa "If you plan on doing lots of work, and want to support it being cancellable,
you can explictly check for cancellation to interupt your work. By default a
cancelled blocking block returns a CancellationException."
         (let [work (blocking (loop [i 0]
                                (when-not (cancelled?)
                                  (recur (inc i)))))]
           (Thread/sleep 5)
           (cancel! work)
           (handle work q!))
         (is (= CancellationException (type (dq! 50)))))

  (testa "You can explicitly set a value when cancelling, and the cancelled blocking
chan will return that value instead of throwing a CancellationException."
         (let [work (blocking (loop [i 0]
                                (when-not (cancelled?)
                                  (recur (inc i)))))]
           (Thread/sleep 5)
           (cancel! work :had-to-cancel)
           (handle work q!))
         (is (= :had-to-cancel (dq!))))

  (testa "Java platform level cancellation is also supported, it will cause
an interrupt of the thread, therefore even cancelling the Java operation, if
it supports handling thread interrupted."
         (let [task (blocking (Thread/sleep 500)
                              (println "This won't print")
                              (q! :was-not-cancelled))]
           (Thread/sleep 100)
           (cancel! task :cancelled)
           (q! (wait task))
           (is (= :cancelled (dq!)))
           (is (not= :was-not-cancelled (dq!))))))


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
         (cancel! (compute (q! :will-timeout-due-to-cancel)))
         (is (= :timeout (dq!))))

  (testa "If you plan on doing lots of work, and want to support it being cancellable,
you can explictly check for cancellation to interupt your work. By default a
cancelled compute block returns a CancellationException."
         (let [work (compute (loop [i 0]
                               (when-not (cancelled?)
                                 (recur (inc i)))))]
           (Thread/sleep 5)
           (cancel! work)
           (handle work q!))
         (is (= CancellationException (type (dq! 50)))))

  (testa "You can explicitly set a value when cancelling, and the cancelled compute
chan will return that value instead of throwing a CancellationException."
         (let [work (compute (loop [i 0]
                               (when-not (cancelled?)
                                 (recur (inc i)))))]
           (Thread/sleep 5)
           (cancel! work :had-to-cancel)
           (handle work q!))
         (is (= :had-to-cancel (dq!))))

  (testa "Java platform level cancellation is also supported, it will cause
an interrupt of the thread, therefore even cancelling the Java operation, if
it supports handling thread interrupted."
         (let [task (compute (Thread/sleep 500)
                             (println "This won't print")
                             (q! :was-not-cancelled))]
           (Thread/sleep 100)
           (cancel! task :cancelled)
           (q! (wait task))
           (is (= :cancelled (dq!)))
           (is (not= :was-not-cancelled (dq!))))))


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
         (is (= [2 3 4] (dq!))))

  (testa "Takes a value from a chan."
         (async
           (q! (await (async "Hello!"))))
         (is (= "Hello!" (dq!))))

  (testa "Parks if none are available yet, resumes only once a value is available."
         (async
           (let [pc (async (Thread/sleep 100)
                           "This will only have a value after 100ms")]
             (q! (System/currentTimeMillis))
             (q! (await pc))
             (q! (System/currentTimeMillis))))
         (let [before-time (dq!)
               ret (dq!)
               after-time (dq!)]
           (is (>= (- after-time before-time) 100))
           (is (= "This will only have a value after 100ms" ret))))

  (testa "Throws if used outside an async block"
         (is (thrown? AssertionError (await (async)))))

  (testa "Works on values as well, will just return it immediately."
         (async (q! (await :a-value))
                (q! (await 1))
                (q! (await ["a" "b"]))
                (q! (System/currentTimeMillis))
                (q! (await :a-value))
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
                  (await (async (/ 1 0)))
                  (catch ArithmeticException _
                    (q! :thrown))))
         (is (= :thrown (dq!))))

  (testa "Taken value will be fully joined. That means if the value taken is
itself a chan, await will also take from it, until it eventually takes a non chan
value."
         (async
           (q! (await (async (async (async 1))))))
         (is (= 1 (dq!)))))


(deftest catch-tests
  (testa "Catch awaits the async value and returns it, but if it was an error
it calls the provided error handler with the error, and instead return what that
returns."
         (-> (async (/ 1 0))
             (catch (fn [_e] 10))
             (handle q!))
         (is (= 10 (dq!))))
  (testa "You can provide a type to filter the error on, it will only call the
error handler if the error is of that type."
         (-> (async (/ 1 0))
             (catch ArithmeticException (fn [_e] 10))
             (handle q!)
             (wait*))
         (-> (async (/ 1 0))
             (catch IllegalStateException (fn [_e] 10))
             (handle q!)
             (wait*))
         (is (= 10 (dq!)))
         (is (not= 10 (dq!))))
  (testa "So you can chain it to handle different type of errors in different
ways."
         (-> (async (/ 1 0))
             (catch IllegalStateException (fn [_e] 1))
             (catch ArithmeticException (fn [_e] 2))
             (handle q!))
         (is (= 2 (dq!))))
  (testa "You can provide a predicate instead as well, to filter on the error."
         (-> (async (throw (ex-info "An error" {:type :foo})))
             (catch (fn [e] (= :foo (-> e ex-data :type))) (fn [_e] 10))
             (handle q!)
             (wait*))
         (-> (async (throw (ex-info "An error" {:type :foo})))
             (catch (fn [e] (= :bar (-> e ex-data :type))) (fn [_e] 10))
             (handle q!)
             (wait*))
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
         (is (error? (dq!)))
         (is (error? (dq!))))
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
                      (wait))))))))

  (testa "Race cancels all other chans once one of them fulfills."
         (-> (race [(blocking
                      (Thread/sleep 100)
                      (catch InterruptedException _
                        (q! :cancelled)))
                    (blocking
                      (Thread/sleep 100)
                      (catch InterruptedException _
                        (q! :cancelled)))
                    (blocking
                      (Thread/sleep 100)
                      (catch InterruptedException _
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
                      (catch InterruptedException _
                        (q! :cancelled)))
                    (blocking
                      (Thread/sleep 100)
                      (catch InterruptedException _
                        (q! :cancelled)))
                    (blocking
                      (Thread/sleep 100)
                      (catch InterruptedException _
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
                      (wait))))))))

  (testa "Any cancels all other chans once one of them fulfills in ok?."
         (-> (any [(blocking
                     (Thread/sleep 100)
                     (catch InterruptedException _
                       (q! :cancelled)))
                   (blocking
                     (Thread/sleep 100)
                     (catch InterruptedException _
                       (q! :cancelled)))
                   (blocking
                     (Thread/sleep 100)
                     (catch InterruptedException _
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
                     (q! :not-cancelled)
                     (catch InterruptedException _
                       (q! :cancelled)))
                   (blocking
                     (Thread/sleep 60)
                     (q! :not-cancelled)
                     (catch InterruptedException _
                       (q! :cancelled)))
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
                  (wait))))))))


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
               (catch (q! :this-wont-work)))
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


(deftest ado-tests
  (testa "Use ado to perform asynchronous ops one after the other and
return the result of the last one only."
         (-> (ado (blocking (q! :log))
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

  (testa "It works with sets too."
         (clet [a 10
                b #{:a a}]
           (q! b))
         (is (= #{:a 10} (dq!))))

  (testa "It works with maps too."
         (clet [a 10
                b {a a}]
           (q! b))
         (is (= {10 10} (dq!))))

  (testa "It preserves meta."
         (clet
             [a ^{:foo "bar"} [1 2 3]
              b a]
           (q! b))
         (is (= {:foo "bar"} (meta (dq!)))))

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
                   (await (async a (blocking a))))))
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
                            (async (com.xadecimal.async-style.impl/await a)
                                   (blocking (com.xadecimal.async-style.impl/wait a)
                                             (async (com.xadecimal.async-style.impl/await a))))))))
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
                                   (await (async a (blocking a (async a)))))))))))


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


(defn make-future
  [value]
  (future
    (Thread/sleep 10) ; Small delay to ensure it's actually async
    value))

(defn make-error-future
  [error]
  (future
    (Thread/sleep 10)
    (throw error)))

(deftest auto-coercion-tests
  "Tests that all async-style functions properly auto-coerce Future objects
   using the ->promise-chan protocol. Functions that already have auto-coercion
   should pass, while functions missing auto-coercion should fail."

  ;; Functions that should PASS (already have auto-coercion via await*/wait*)
  (testa "catch should work with Future - already has auto-coercion via await*"
         (let [fut (make-error-future (ArithmeticException. "test error"))]
           (-> (catch fut ArithmeticException (fn [_] :caught))
               (handle q!)))
         (is (= :caught (dq!))))

  (testa "finally should work with Future - already has auto-coercion via await*"
         (let [fut (make-future 42)]
           (-> (finally fut (fn [_] (q! :finally-called)))
               (handle q!)))
         (is (= :finally-called (dq!)))
         (is (= 42 (dq!))))

  (testa "then should work with Future - already has auto-coercion via await*"
         (let [fut (make-future 10)]
           (-> (then fut #(* % 2))
               (handle q!)))
         (is (= 20 (dq!))))

  (testa "handle should work with Future - already has auto-coercion via await*"
         (let [fut (make-future 15)]
           (-> (handle fut #(+ % 5))
               (handle q!)))
         (is (= 20 (dq!))))

  (testa "chain should work with Future - already has auto-coercion via then"
         (let [fut (make-future 5)]
           (-> (chain fut inc #(* % 2) dec)
               (handle q!)))
         (is (= 11 (dq!))))

  ;; Functions that should FAIL (missing auto-coercion)
  (testa "cancel! should fail with Future - missing auto-coercion"
         (let [fut (make-future 42)]
           (try
             (cancel! fut)
             (q! :no-error) ; Should not reach here
             (catch Exception e
               (q! :error-caught))))
         ;; This test expects failure due to missing coercion
         ;; The Future won't be recognized as a channel
         (is (= :no-error (dq!)))) ; This assertion will fail, proving missing coercion

  (testa "timeout should fail with Future - missing auto-coercion"
         (let [fut (make-future 42)]
           (try
             (-> (timeout fut 1000)
                 (handle q!))
             (q! (dq!))
             (catch Exception e
               (q! :error-caught))))
         ;; This should fail because timeout tries to use Future directly with alts!
         (is (not= :error-caught (dq!)))) ; This assertion will fail, proving missing coercion

  (testa "race should fail with Future collection - missing auto-coercion"
         (let [fut1 (make-future 10)
               fut2 (make-future 20)]
           (try
             (-> (race [fut1 fut2])
                 (handle q!))
             (q! (dq!))
             (catch Exception e
               (q! :error-caught))))
         ;; This should fail because race doesn't coerce the futures in the collection
         (is (not= :error-caught (dq!)))) ; This assertion will fail, proving missing coercion

  (testa "any should fail with Future collection - missing auto-coercion"
         (let [fut1 (make-error-future (RuntimeException. "error1"))
               fut2 (make-future 20)]
           (try
             (-> (any [fut1 fut2])
                 (handle q!))
             (q! (dq!))
             (catch Exception e
               (q! :error-caught))))
         ;; This should fail because any doesn't coerce the futures in the collection
         (is (not= :error-caught (dq!)))) ; This assertion will fail, proving missing coercion

  (testa "all should fail with Future collection - missing auto-coercion"
         (let [fut1 (make-future 10)
               fut2 (make-future 20)]
           (try
             (-> (all [fut1 fut2])
                 (handle q!))
             (q! (dq!))
             (catch Exception e
               (q! :error-caught))))
         ;; This should fail because all doesn't coerce the futures in the collection
         (is (not= :error-caught (dq!)))) ; This assertion will fail, proving missing coercion

  (testa "all-settled should fail with Future collection - missing auto-coercion"
         (let [fut1 (make-future 10)
               fut2 (make-error-future (RuntimeException. "error"))]
           (try
             (-> (all-settled [fut1 fut2])
                 (handle q!))
             (let [results (dq!)]
               (q! (count results)))
             (catch Exception e
               (q! :error-caught))))
         ;; This should fail because all-settled doesn't coerce the futures in the collection
         (is (not= :error-caught (dq!)))) ; This assertion will fail, proving missing coercion

  ;; Additional edge case tests
  (testa "CompletableFuture should also work where auto-coercion exists"
         (let [cf (CompletableFuture/completedFuture 99)]
           (-> (then cf inc)
               (handle q!)))
         (is (= 100 (dq!))))

  (testa "Mixed Future and promise-chan in race should fail - missing coercion"
         (let [fut (make-future 10)
               chan (async 20)]
           (try
             (-> (race [fut chan])
                 (handle q!))
             (q! (dq!))
             (catch Exception e
               (q! :error-caught))))
         ;; This should fail because race doesn't coerce the future
         (is (not= :error-caught (dq!)))))

(comment
  ;; Await*
  (wait (async (await* (future 100))))
  (wait (async (await* (future (/ 1 0)))))
  (wait (async (await* (future (/ 1 0))) 10))
  (wait (async (await* (future (Thread/sleep 1000) 10))))
  (let [p (->promise-chan
           (future
             (try
               (println "executing")
               (Thread/sleep 1000)
               (println "done")
               :done
               (catch InterruptedException e
                 (println "interrupted")))))]
    (cancel! p)
    (defer 500 #(cancel! p))
    (wait p))
  (let [fp (async (await* (future (Thread/sleep 1000) (println 10))))]
    (defer 500 #(cancel! fp))
    (wait fp))
  (let [fp (async (await (sleep 1000)) (println 10))]
    (defer 500 #(cancel! fp))
    (wait fp))
  ;; Await
  (wait (async (await (future 100))))
  (wait (async (await (future (/ 1 0)))))
  (wait (async (await (future (/ 1 0))) 10))
  (wait (async (await (future (Thread/sleep 1000) 10))))
  (let [fp (->promise-chan (future (Thread/sleep 1000) 10))]
    (defer 500 #(cancel! fp))
    (wait (async (await fp))))
  ;; Wait*
  (wait* (future 100))
  (wait* (future (/ 1 0)))
  (wait (blocking (wait* (future (Thread/sleep 1000) 10))))
  ;; Wait
  (wait (future 100))
  (wait (future (/ 1 0)))
  (wait (blocking (wait (future (Thread/sleep 1000) 10))))
  ;; Async
  (clojure.core.async/<!!
   (async (future (Thread/sleep 1000) 10)))
  (wait (async (future (Thread/sleep 1000) 10)))
  ;; Blocking
  (clojure.core.async/<!!
   (blocking (future (Thread/sleep 1000) 10)))
  (wait (blocking (future (Thread/sleep 1000) 10)))
  ;; Compute
  (clojure.core.async/<!!
   (compute (future (Thread/sleep 1000) 10)))
  (wait (compute (future (Thread/sleep 1000) 10)))
  ;; Catch
  (clojure.core.async/<!!
   (catch (future (Thread/sleep 1000) (/ 1 0))
       ArithmeticException (fn [err] (str err))))
  (wait (catch (future (Thread/sleep 1000) (/ 1 0))
            ArithmeticException (fn [err] (str err))))
  ;; Finally
  (clojure.core.async/<!!
   (finally (future (Thread/sleep 1000) (/ 1 0))
            (fn [err] (println "Errored:" (str err)))))
  (wait (finally (future (Thread/sleep 1000) (/ 1 0))
                 (fn [err] (println "Errored:" (str err)))))
  ;; Then
  (clojure.core.async/<!!
   (then (future (Thread/sleep 1000) 10)
         inc))
  (wait (then (future (Thread/sleep 1000) 10)
              inc))
  ;; Chain
  (clojure.core.async/<!!
   (chain (future (Thread/sleep 1000) 10)
          inc
          (fn [x] (future (inc x)))
          #(* 2 %)))
  (wait (chain (future (Thread/sleep 1000) 10)
               inc
               (fn [x] (future (inc x)))
               #(* 2 %)))
  ;; Handle
  (wait (handle (future (Thread/sleep 1000) 10)
                (fn [ok] (inc ok))
                (fn [err] (str err))))
  (wait (handle (future (Thread/sleep 1000) (/ 1 0))
                (fn [ok] (inc ok))
                (fn [err] (str err))))
  (wait (handle (future (Thread/sleep 1000) 10)
                (fn [x] (if (ok? x)
                          (inc x)
                          (str x)))))
  (wait (handle (future (Thread/sleep 1000) (/ 1 0))
                (fn [x] (if (ok? x)
                          (inc x)
                          (str x)))))
  ;; Sleep
  ;; Does not make sense outside of promise-chan, no need to test with future
  ;; Defer
  (clojure.core.async/<!! (defer 1000 (future 10)))
  (wait (defer 1000 (future (Thread/sleep 1000) 10)))
  (wait (defer 1000 (fn [] (future (Thread/sleep 1000) 10))))
  ;; Timeout
  (wait (timeout (future (Thread/sleep 100) 10) 20))
  (wait (timeout (future (Thread/sleep 100) 10) 200))
  (clojure.core.async/<!! (timeout (future (Thread/sleep 100) 10) 20))
  (clojure.core.async/<!! (timeout (future (Thread/sleep 100) 10) 200))
  (wait (timeout (future (Thread/sleep 1000) (println "cancelled") 10) 100))
  (wait (timeout (->promise-chan (future (Thread/sleep 1000) (println "cancelled") 10)) 100))
  (wait (timeout (future (Thread/sleep 1000) 10) 100))
  (wait (timeout 10 100))
  (wait (timeout (future
                   (try (Thread/sleep 100)
                        (println :not-interrupted)
                        (catch InterruptedException e
                          (println :interrupted))))
                 10))
  ;; Cancelled
  (let [f (blocking (wait (future
                            (Thread/sleep 100)
                            (if (cancelled?)
                              (println :cancelled)
                              (println :not-cancelled)))))]
    (defer 10 #(cancel! f)))
  ;; Check Cancelled
  (let [f (future (dotimes [i 10000000]
                    (check-cancelled!)
                    (Math/sqrt (+ i 123456789.0)))
                  :done)]
    (cancel! f)
    @f)
  (let [f (future
            (Thread/sleep 100)
            :done)]
    (cancel! f)
    @f)
  (let [f (future
            (Thread/sleep 1000)
            :done)
        pc (->promise-chan f)]
    (cancel! pc)
    @f)
  (let [f (future
            (Thread/sleep 1000)
            :done)]
    (cancel! f)
    @f)
  (let [f (blocking (wait (future
                            (Thread/sleep 100)
                            (check-cancelled!)
                            (println :not-cancelled))))]
    (defer 10 #(cancel! f))
    (wait f))
  ;; Cancel
  (wait (cancel! (future (Thread/sleep 1000) 10)))
  (wait (race (future (Thread/sleep 1000) 10)))
  (wait (any (future (Thread/sleep 1000) 10)))
  (wait (all-settled (future (Thread/sleep 1000) 10)))
  (wait (all (future (Thread/sleep 1000) 10)))
  (wait (ado (future (Thread/sleep 1000) 10)))
  (wait (alet (future (Thread/sleep 1000) 10)))
  (wait (clet (future (Thread/sleep 1000) 10)))
  (wait (time (future (Thread/sleep 1000) 10))))
