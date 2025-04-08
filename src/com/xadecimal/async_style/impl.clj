(ns com.xadecimal.async-style.impl
  (:refer-clojure :exclude [await time])
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.dispatch :as d])
  (:import [clojure.core.async.impl.channels ManyToManyChannel]
           [clojure.lang Agent]
           [java.util.concurrent CancellationException TimeoutException]))


;; TODO: add support for CSP style, maybe a process-factory that creates processes with ins/outs channels of buffer 1 and connectors between them
;; TODO: Add ClojureScript support


(def ^:private compute-pool
  "the clojure.core Agent pooledExecutor, it is fixed size bounded to cpu cores
   + 2 and pre-allocated, use it for heavy computation, don't block it"
  Agent/pooledExecutor) ; Fixed bounded to cpu core + 2 and pre-allocated

(def ^:private blocking-pool
  "the core.async thread block executor, it is caching, unbounded and not
   pre-allocated, use it for blocking operations and blocking io"
  @#'a/thread-macro-executor) ; Used by a/thread

(def ^:private async-pool
  "the core.async go block executor, it is fixed size, defaulting to 8 threads,
   don't soft or hard block it"
  @d/executor) ; Used by a/go


(defn- implicit-try
  "Wraps body in an implicit (try body) and uses the last forms of body if they
   are one or more catch, a finally or a combination of those as the catch(s) and
   finally block of the try.

   Example:
    (implicit-try '((println 100) (/ 1 0) (catch ArithmeticException e (println e))))
    => ((try (println 100) (/ 1 0) (catch ArithmeticException e (println e))))"
  [body]
  (let [[try' rem] (split-with #(or (not (seqable? %)) (not (#{'catch 'finally} (first %)))) body)
        [catches rem] (split-with #(and (seqable? %) (= 'catch (first %))) rem)
        finally (or (when (and (seqable? (first rem)) (= 'finally (ffirst rem))) (first rem))
                    (when (and (seqable? (second rem)) (= 'finally (-> rem second first))) (second rem)))]
    (when (not= `(~@try' ~@(when catches catches) ~@(when finally [finally])) body)
      (throw (ex-info "Bad syntax, form must either not have a catch and finally block, or it must end with one or more catch blocks followed by a finally block in that order, or it must end with one or more catch blocks, or it must end with a single finally block." {})))
    `(~@(if (not (or (seq catches) finally))
          body
          [`(try
              ~@try'
              ~@(when catches
                  catches)
              ~@(when finally
                  [finally]))]))))

(defn- settle
  "Puts v into chan if it is possible to do so immediately (uses offer!) and
   closes chan. If v is nil it will just close chan. Returns true if offer! of v
   in chan succeeded or v was nil, false otherwise."
  [chan v]
  (if (nil? v)
    (do
      (a/close! chan)
      true)
    (let [ret (a/offer! chan v)]
      (a/close! chan)
      ret)))

(defn error?
  "Returns true if v is considered an error as per async-style's error
   representations, false otherwise. Valid error representations in async-style
   for now are:
     * instances of Throwable"
  [v]
  (instance? Throwable v))

(defn ok?
  "Returns true if v is not considered an error as per async-style's error
   representations, false otherwise. Valid error representations in async-style
   for now are:
     * instances of Throwable"
  [v]
  (not (error? v)))

(defn- chan?
  "Returns true if v is a core.async channel, false otherwise."
  [v]
  (instance? ManyToManyChannel v))

(def ^:private ^:dynamic *cancellation-chan*)
(alter-meta! #'*cancellation-chan* assoc :doc
             "Used by the cancellation machinery, will be bound to a channel
that will indicate if the current execution has been cancelled.
Users are expected when inside an execution block like async,
blocking or compute to check this channel using cancelled? to
see if someone tried to cancel their execution, in which case
they should short-circuit as soon as they can.")

(defn- cancelled-val?
  "Returns true if v indicates a cancellation as per async-style's cancellation
   representations, false otherwise. Valid cancellation representations in
   async-style for now are:
     * all non-nil values"
  [v]
  (not (nil? v)))

(defn cancelled?
  "Returns true if execution context was cancelled and thus should be
   interrupted/short-circuited, false otherwise.

   Users are expected, when inside an execution block like async, blocking or
   compute, to check using (cancelled? or check-cancelled!) as often as they can in case someone
   tried to cancel their execution, in which case they should
   interrupt/short-circuit the work as soon as they can."
  []
  (if-let [v (a/poll! *cancellation-chan*)]
    (cancelled-val? v)
    false))

(defn check-cancelled!
  "Throws if execution context was cancelled and thus should be
   interrupted/short-circuited, returns nil.

   Users are expected, when inside an execution block like async, blocking or
   compute, to check using (cancelled? or check-cancelled!) as often as they can in case someone
   tried to cancel their execution, in which case they should
   interrupt/short-circuit the work as soon as they can."
  []
  (when-let [v (a/poll! *cancellation-chan*)]
    (when (cancelled-val? v)
      (throw (CancellationException.)))))

(defn cancel
  "When called on chan, tries to tell processes currently executing over the
   chan that they should interrupt and short-circuit (aka cancel) their execution
   as soon as they can, as it is no longer needed.

   The way cancellation is conveyed is by settling the return channel of async,
   blocking and compute blocks to a CancellationException, unless passed a v
   explicitly, in which case it will settle it with v.

   That means by default a block that has its execution cancelled will return a
   CancellationException and thus awaiters and other takers of its result will
   see the exception and can handle it accordingly. If instead you want to cancel
   the block so it returns a value, pass in a v and the awaiters and
   takers will receive that value instead.

   It is up to processes inside async, blocking and compute blocks to properly
   check for cancellation on a channel."
  ([chan]
   (when (chan? chan)
     (settle chan (CancellationException. "Operation was cancelled."))))
  ([chan v]
   (when (chan? chan)
     (settle chan v))))

(defn- compute-call
  "Executes f in the compute-pool, returning immediately to the calling thread.
   Returns a channel which will receive the result of calling f when completed,
   then close."
  [f]
  (let [c (a/chan 1)]
    (let [binds (clojure.lang.Var/getThreadBindingFrame)]
      (.execute compute-pool
                (fn []
                  (clojure.lang.Var/resetThreadBindingFrame binds)
                  (try
                    (let [ret (f)]
                      (when-not (nil? ret)
                        (a/>!! c ret)))
                    (finally
                      (a/close! c))))))
    c))

(defmacro compute'
  "Executes the body in the compute-pool, returning immediately to the calling
   thread. Returns a channel which will receive the result of the body when
   completed, then close."
  [& body]
  (let [compute-call- compute-call]
    `(~compute-call- (^:once fn* [] ~@body))))

(defn- async'
  "Wraps body in a way that it executes in an async or blocking block with
   support for cancellation, implicit-try, and returning a promise-chan settled
   with the result or any exception thrown."
  [body execution-type]
  (let [settle- settle]
    `(let [ret# (a/promise-chan)]
       (~(case execution-type
           :blocking `a/thread
           :async `a/go
           :compute `compute')
        (binding [*cancellation-chan* ret#]
          (when-not (cancelled?)
            (~settle- ret#
             (try ~@(implicit-try body)
                  (catch Throwable t#
                    t#))))))
       ret#)))

(defmacro async
  "Asynchronously execute body on the async-pool with support for cancellation,
   implicit-try, and returning a promise-chan settled with the result or any
   exception thrown.

   body will run on the async-pool, so if you plan on doing something blocking
   or compute heavy, use blocking or compute instead."
  [& body]
  (async' body :async))

(defmacro blocking
  "Asynchronously execute body on the blocking-pool with support for
   cancellation, implicit-try, and returning a promise-chan settled with the
   result or any exception thrown.

   body will run on the blocking-pool, so use this when you will be blocking or
   doing blocking io only."
  [& body]
  (async' body :blocking))

(defmacro compute
  "Asynchronously execute body on the compute-pool with support for
   cancellation, implicit-try, and returning a promise-chan settled with the
   result or any exception thrown.

   body will run on the compute-pool, so use this when you will be doing heavy
   computation, and don't block, if you're going to block use blocking
   instead. If you're doing a very small computation, like polling another chan,
   use async instead."
  [& body]
  (async' body :compute))

(defn- join'
  "Parking take from chan, but if result taken is still a chan?, further parking
   take from it, repeating until first non chan? result and return it."
  [chan]
  (let [chan?- chan?]
    `(loop [res# (a/<! ~chan)]
       (if (~chan?- res#)
         (recur (a/<! res#))
         res#))))

(defn- <<!'
  "Wraps chan-or-value so that if chan it joins from it returning the joined
   result, else if value it returns value directly, or if chan-or-value throws it
   returns the thrown exception."
  [chan-or-value]
  (let [chan-or-value-gensym (gensym 'chan-or-value)
        chan?- chan?]
    `(let [~chan-or-value-gensym (try ~chan-or-value
                                      (catch Throwable t#
                                        t#))
           value-or-error# (if (~chan?- ~chan-or-value-gensym)
                             ~(join' chan-or-value-gensym)
                             ~chan-or-value-gensym)]
       value-or-error#)))

(defmacro <<!
  "Parking takes from chan-or-value so that any exception is returned, and with
   taken result fully joined."
  [chan-or-value]
  (<<!' chan-or-value))

(defn <<!!
  "Blocking takes from chan-or-value so that any exception is returned, and with
   taken result fully joined."
  [chan-or-value]
  (if (chan? chan-or-value)
    (loop [res (a/<!! chan-or-value)]
      (if (chan? res)
        (recur (a/<!! res))
        res))
    chan-or-value))

(defn- <<?'
  "Wraps chan-or-value so that if chan it joins from it returning the joined
   result, else if value it returns value directly, or if chan-or-value throws it
   re-throws exception."
  [chan-or-value]
  `(let [value-or-error# (<<! ~chan-or-value)]
     (if (error? value-or-error#)
       (throw value-or-error#)
       value-or-error#)))

(defmacro <<?
  "Parking takes from chan-or-value so that any exception taken is re-thrown,
   and with taken result fully joined. Supports implicit-try to handle thrown
   exceptions such as:

   (async
     (<<? (async (/ 1 0))
          (catch ArithmeticException e
            (println e))
          (catch Exception e
            (println \"Other unexpected excpetion\"))
          (finally (println \"done\"))))"
  [chan-or-value & body]
  (first (implicit-try (cons (<<?' chan-or-value) body))))

(defn- <<??'
  "Wraps chan-or-value so that if chan it joins from it returning the joined
   result, else if value it returns value directly, or if chan-or-value throws it
   re-throws exception."
  [chan-or-value]
  `(let [value-or-error# (<<!! ~chan-or-value)]
     (if (error? value-or-error#)
       (throw value-or-error#)
       value-or-error#)))

(defmacro <<??
  "Blocking takes from chan-or-value so that any exception taken is re-thrown,
   and with taken result fully joined. Supports implicit-try to handle thrown
   exceptions such as:

   (<<? (async (/ 1 0))
          (catch ArithmeticException e
            (println e))
          (catch Exception e
            (println \"Other unexpected excpetion\"))
          (finally (println \"done\")))"
  [chan-or-value & body]
  (first (implicit-try (cons (<<??' chan-or-value) body))))

(defmacro await
  "Parking takes from chan-or-value so that any exception taken is re-thrown,
   and with taken result fully joined.

   Same as <<?

   Supports implicit-try to handle thrown exceptions such as:

   (async
     (await (async (/ 1 0))
            (catch ArithmeticException e
              (println e))
            (catch Exception e
              (println \"Other unexpected excpetion\"))
            (finally (println \"done\"))))"
  [chan-or-value & body]
  (first (implicit-try (cons (<<?' chan-or-value) body))))

(defmacro wait
  "Blocking takes from chan-or-value so that any exception taken is re-thrown,
   and with taken result fully joined.

   Same as <<??

   Supports implicit-try to handle thrown exceptions such as:

   (wait (async (/ 1 0))
         (catch ArithmeticException e
           (println e))
         (catch Exception e
           (println \"Other unexpected excpetion\"))
         (finally (println \"done\")))"
  [chan-or-value & body]
  (first (implicit-try (cons (<<??' chan-or-value) body))))

(defn catch
  "Parking takes fully joined value from chan. If value is an error of
   pred-or-type, will call error-handler with it.

   Returns a promise-chan settled with the value or the return of the
   error-handler.

   error-handler will run on the async-pool, so if you plan on doing something
   blocking or compute heavy, remember to wrap it in a blocking or compute
   respectively."
  ([chan error-handler]
   (async
     (let [v (<<! chan)]
       (if (error? v)
         (error-handler v)
         v))))
  ([chan pred-or-type error-handler]
   (async
     (let [v (<<! chan)]
       (if (error? v)
         (cond (and (class? pred-or-type) (instance? pred-or-type v))
               (error-handler v)
               (and (ifn? pred-or-type) (pred-or-type v))
               (error-handler v)
               :else v)
         v)))))

(defn finally
  "Parking takes fully joined value from chan, and calls f with it no matter if
   the value is ok? or error?.

   Returns a promise-chan settled with the taken value, and not the return of f,
   which means f is implied to be doing side-effect(s).

   f will run on the async-pool, so if you plan on doing something blocking or
   compute heavy, remember to wrap it in a blocking or compute respectively."
  [chan f]
  (async
    (let [res (<<! chan)]
      (f res)
      res)))

(defn then
  "Asynchronously executes f with the result of chan once available, unless chan
   results in an error, in which case f is not executed.

   Returns a promise-chan settled with the result of f or the error.

   f will run on the async-pool, so if you plan on doing something blocking or
   compute heavy, remember to wrap it in a blocking or compute respectively."
  [chan f]
  (async
    (let [v (<<! chan)]
      (if (error? v)
        v
        (f v)))))

(defn chain
  "Chains multiple then together starting with chan like:
     (-> chan (then f1) (then f2) (then fs) ...)

   fs will all run on the async-pool, so if you plan on doing something blocking
   or compute heavy, remember to wrap it in a blocking or compute respectively."
  [chan & fs]
  (reduce
   (fn [chan f] (then chan f))
   chan fs))

(defn handle
  "Asynchronously executes f with the result of chan once available (f result),
   unlike then, handle will always execute f, when chan's result is an error f is
   called with the error (f error).

   Returns a promise-chan settled with the result of f.

   Alternatively, one can pass an ok-handler and an error-handler and the
   respective one will be called based on if chan's result is ok (ok-handler
   result) or an error (error-handler error).

   f, ok-handler and error-handler will all run on the async-pool, so if you
   plan on doing something blocking or compute heavy, remember to wrap it in a
   blocking or compute respectively."
  ([chan f]
   (async
     (let [v (<<! chan)]
       (f v))))
  ([chan ok-handler error-handler]
   (async
     (let [v (<<! chan)]
       (if (error? v)
         (error-handler v)
         (ok-handler v))))))

(defn sleep
  "Asynchronously sleep ms time, returns a promise-chan which settles after ms
   time."
  [ms]
  (async (a/<! (a/timeout ms))))

(defn defer
  "Waits ms time and then asynchronously executes value-or-fn, returning a
   promsie-chan settled with the result.

   value-or-fn will run on the async-pool, so if you plan on doing something
   blocking or compute heavy, remember to wrap it in a blocking or compute
   respectively."
  [ms value-or-fn]
  (async (a/<! (a/timeout ms))
         (when-not (cancelled?)
           (if (fn? value-or-fn)
             (value-or-fn)
             value-or-fn))))

(defn timeout
  "If chan fulfills before ms time has passed, return a promise-chan settled
   with the result, else returns a promise-chan settled with a TimeoutException
   or the result of timed-out-value-or-fn.

   In the case of a timeout, chan will be cancelled.

   timed-out-value-or-fn will run on the async-pool, so if you plan on doing
   something blocking or compute heavy, remember to wrap it in a blocking or
   compute respectively."
  ([chan ms]
   (timeout chan ms (TimeoutException. (str "Channel timed out: " ms "ms."))))
  ([chan ms timed-out-value-or-fn]
   (async (let [deferred (defer ms ::timed-out)
                res (first (a/alts! [chan deferred]))]
            (cond (= ::timed-out res)
                  (do
                    (cancel chan)
                    (if (fn? timed-out-value-or-fn)
                      (timed-out-value-or-fn)
                      timed-out-value-or-fn))
                  :else
                  (do (cancel deferred)
                      res))))))

(defn race
  "Returns a promise-chan that settles as soon as one of the chan in chans
   fulfill, with the value taken (and joined) from that chan.

   Unlike any, this will also return the first error? to be returned by one of
   the chans. So if the first chan to fulfill does so with an error?, race will
   return a promise-chan settled with that error.

   Once a chan fulfills, race cancels all the others."
  [chans]
  (let [ret (a/promise-chan)]
    (if (seq chans)
      (doseq [chan chans]
        (a/go
          (let [res (<<! chan)]
            (and (settle ret res)
                 (run! #(when-not (= chan %) (cancel %)) chans)))))
      (settle ret nil))
    ret))

(defn any
  "Returns a promise-chan that settles as soon as one of the chan in chans
   fulfills in ok?, with the value taken (and joined) from that chan.

   Unlike race, this will ignore chans that fulfilled with an error?. So if the
   first chan to fulfill does so with an error?, any will keep waiting for
   another chan to eventually fulfill in ok?.

   If all chans fulfill in error?, returns an error containing the list of all
   the errors.

   Once a chan fulfills with an ok?, any cancels all the others."
  [chans]
  (let [ret (a/promise-chan)
        attempt-chans (volatile! [])]
    (if (seq chans)
      (do
        (doseq [chan chans]
          (vswap! attempt-chans
                  conj
                  (a/go
                    (let [v (<<! chan)]
                      (if (error? v)
                        v
                        (and (settle ret v)
                             (run! #(when-not (= chan %) (cancel %)) chans)))))))
        (a/go
          (let [errors (a/<! (a/map vector @attempt-chans))]
            (when (every? error? errors)
              (settle ret (ex-info
                           "All chans returned errors"
                           {:block :any
                            :errors errors
                            :type :all-errored}))))))
      (settle ret nil))
    ret))

(defn all-settled
  "Takes a seqable of chans as an input, and returns a promise-chan that settles
   after all of the given chans have fulfilled in ok? or error?, with a vector of
   the taken ok? results and error? results of the input chans.

   It is typically used when you have multiple asynchronous tasks that are not
   dependent on one another to complete successfully, or you'd always like to
   know the result of each chan even when one errors.

   In comparison, the promise-chan returned by all may be more appropriate if
   the tasks are dependent on each other / if you'd like to immediately stop upon
   any of them returning an error?."
  [chans]
  (async
    (loop [res [] chan (first chans) chans (next chans)]
      (if chan
        (recur (conj res (<<! chan))
               (first chans)
               (next chans))
        res))))

(defn all
  "Takes a seqable of chans as an input, and returns a promise-chan that settles
   after all of the given chans have fulfilled in ok?, with a vector of the taken
   ok? results of the input chans. This returned promise-chan will settle when
   all of the input's chans have fulfilled, or if the input seqable contains no
   chans (only values or empty). It settles in error? immediately upon any of the
   input chans returning an error? or non-chans throwing an error?, and will
   contain the error? of the first taken chan to return one."
  [chans]
  (let [ret (a/promise-chan)
        res-chans (volatile! [])]
    (if (seq chans)
      (do
        (doseq [chan chans]
          (vswap! res-chans
                  conj
                  (a/go
                    (let [v (<<! chan)]
                      (if (error? v)
                        (do (and (settle ret v)
                                 (run! #(when-not (= chan %) (cancel %)) chans))
                            v)
                        v)))))
        (a/go
          (let [results (a/<! (a/map vector @res-chans))]
            (when-not (some error? results)
              (settle ret results)))))
      (a/close! ret))
    ret))

(defmacro do!
  "Execute expressions one after the other, awaiting the result of each one
   before moving on to the next. Results are lost to the void, same as
   clojure.core/do, so side effects are expected. Returns a promise-chan which
   settles with the result of the last expression when the entire do! is done."
  [& exprs]
  `(async
     ~@(map #(list `await %) exprs)))

(defmacro alet
  "Asynchronous let. Binds result of async expressions to local binding, executing
   bindings in order one after the other."
  [bindings & exprs]
  `(async
     (clojure.core/let
         [~@(mapcat
             (fn [[sym val]] [`~sym `(await ~val)])
             (partition 2 bindings))]
       ~@exprs)))

(defmacro clet
  "Concurrent let. Executes all bound expressions in an async block so that
   the bindings run concurrently. If a later binding or the body depends on an
   earlier binding, that reference is automatically replaced with an await.
   In a blocking/compute context, await is transformed to wait for proper
   blocking behavior.

   Notes:
     * Bindings are evaluated in the async-pool; therefore, they should not
       perform blocking I/O or heavy compute directly. If you need to do blocking
       operations or heavy compute, wrap the binding in a blocking or compute call.
     * This macro only supports simple symbol bindings; destructuring (vector or
       map destructuring) is not supported.
     * It will transform symbols even inside quoted forms, so literal code in quotes
       may be rewritten unexpectedly.
     * Inner local bindings (e.g. via a nested let) that shadow an outer binding are
       not handled separately; the macro will attempt to rewrite every occurrence,
       which may lead to incorrect replacements.
     * Anonymous functions that use parameter names identical to outer bindings
       will also be rewritten, which can cause unintended behavior if they are meant
       to shadow those bindings."
  [bindings & body]
  (letfn [(rebuild-form [form new-coll]
            (if (seq? form)
              (with-meta (apply list new-coll) (meta form))
              (with-meta (into (empty form) new-coll) (meta form))))
          (transform-form [env form blocking?]
            (cond
              ;; Async call: reset blocking context for subforms.
              (and (seq? form)
                   (symbol? (first form))
                   (#{'async `async} (first form)))
              (rebuild-form form
                            (cons (first form)
                                  (map #(transform-form env % false) (rest form))))
              ;; In a blocking context, rewrite (await …) to (wait …)
              (and blocking?
                   (seq? form)
                   (symbol? (first form))
                   (#{'await `await} (first form)))
              (rebuild-form form
                            (cons `wait
                                  (map #(transform-form env % blocking?) (rest form))))
              ;; Replace a symbol from our environment.
              (and (symbol? form)
                   (contains? env form))
              (if blocking?
                (second (env form))
                (first (env form)))
              ;; Entering a blocking/compute form: set blocking flag.
              (and (seq? form)
                   (symbol? (first form))
                   (#{'blocking `blocking 'compute `compute} (first form)))
              (rebuild-form form
                            (map #(transform-form env % true) form))
              ;; Otherwise, if it's a sequence, walk its elements.
              (seq? form)
              (rebuild-form form
                            (map #(transform-form env % blocking?) form))
              ;; If it's a map, walk it's k/v pairs.
              (map? form)
              (rebuild-form form
                            (map (fn [[k v]]
                                   [(transform-form env k blocking?)
                                    (transform-form env v blocking?)])
                                 form))
              ;; For other collections, walk their elements.
              (coll? form)
              (rebuild-form form
                            (map #(transform-form env % blocking?) form))
              :else form))
          (process-bindings [binding-pairs]
            (reduce (fn [[env binds] [sym val]]
                      (let [new-val (transform-form env val false)
                            ;; Map each bound symbol to a vector: [ (await sym) (wait sym) ]
                            new-env (assoc env sym [(list `await sym)
                                                    (list `wait sym)])]
                        [new-env (conj binds [sym `(async ~new-val)])]))
                    [{} []]
                    binding-pairs))]
    (let [[final-env binds] (process-bindings (partition 2 bindings))
          body-form (transform-form final-env
                                    (if (> (count body) 1)
                                      (cons `do body)
                                      (first body))
                                    false)]
      `(let [~@(apply concat binds)]
         (async ~body-form)))))

(defmacro time
  "Evaluates expr and prints the time it took. Returns the value of expr. If
   expr evaluates to a channel, it waits for channel to fulfill before printing
   the time it took."
  ([expr]
   `(time ~expr (fn [time-ms#] (prn (str "Elapsed time: " time-ms# " msecs")))))
  ([expr print-fn]
   (let [chan?- chan?]
     `(let [start# (System/nanoTime)
            prn-time-fn# (fn prn-time-fn#
                           ([~'_] (prn-time-fn#))
                           ([] (~print-fn (/ (double (- (System/nanoTime) start#)) 1000000.0))))
            ret# ~expr]
        (if (~chan?- ret#)
          (-> ret# (handle prn-time-fn#))
          (prn-time-fn#))
        ret#))))
