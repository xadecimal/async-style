(ns com.xadecimal.async-style.impl
  (:refer-clojure :exclude [areduce await time])
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.dispatch :as d]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.core.protocols :as p]
            [com.xadecimal.async-style.protocols :refer [IntoPromiseChan ->promise-chan]])
  (:import [clojure.core.async.impl.buffers PromiseBuffer]
           [clojure.core.async.impl.channels ManyToManyChannel]
	         [clojure.lang Agent IBlockingDeref]
	         [java.util.concurrent CancellationException TimeoutException
	          ThreadPoolExecutor CompletableFuture Future ExecutionException]
	         [java.util.concurrent.locks ReentrantLock]
	         [java.util WeakHashMap]))


;; TODO: add support for CSP style, maybe a process-factory that creates processes with ins/outs channels of buffer 1 and connectors between them
;; TODO: Add ClojureScript support
;; TODO: Consider adding resolved, rejected and try, similar to the JS Promise APIs
;; TODO: Consider supporting await for... like in Python or JS
;; TODO: Consider if I should wrap the returned promise-chan in my own type, then I could have a proper cancel-chan and other stuff on it as well, instead of cramming all semantics on the promise-chan


(def ^:private executor-for
  "the core.async 1.8+ executor-for var, nil if we're on an older version of
   core.async that doesn't have it"
  (requiring-resolve `d/executor-for))

(def ^:private ^ThreadPoolExecutor compute-pool
  "the clojure.core Agent pooledExecutor, it is fixed size bounded to cpu cores
   + 2 and pre-allocated, use it for heavy computation, don't block it"
  Agent/pooledExecutor) ; Fixed bounded to cpu core + 2 and pre-allocated

(def ^:private blocking-pool
  "the core.async thread block executor, it is caching, unbounded and not
   pre-allocated, use it for blocking operations and blocking io"
  (if executor-for
    ;; Used by a/io-thread
    (@executor-for :io)
    ;; Used by a/thread
    @#'a/thread-macro-executor))

(def ^:private async-pool
  "the core.async go block executor, it is fixed size, defaulting to 8 threads,
   don't soft or hard block it"
  (if executor-for
    ;; Used by a/go in 1.8+
    (@executor-for :core-async-dispatch)
    ;; Used by a/go
    @d/executor))

(def ^WeakHashMap cancellation-callbacks
  (WeakHashMap.))


(defn- make-cancellation-exception
  []
  (CancellationException. "Operation was cancelled."))

(defn make-interrupted-exception
  []
  (InterruptedException. "Operation was interrupted."))

(defn settle-immediately!
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

(def ^:dynamic *cancellation-chan*)
(alter-meta! #'*cancellation-chan* assoc :doc
             "Used by the cancellation machinery, will be bound to a channel
that will indicate if the current execution has been cancelled.
Users are expected when inside an execution block like async,
blocking or compute to check this channel using cancelled? to
see if someone tried to cancel their execution, in which case
they should short-circuit as soon as they can.")

(defn cancellation-chan-bound?
  "Returns true if *cancellation-chan* is bound and non-detached,
   false otherwise."
  []
  (and (bound? #'*cancellation-chan*) (not= *cancellation-chan* ::detached)))

(defmacro detach
  "Runs body outside the current cancellation context.

   Use when spawning work that should not be cancelled when the parent is
   cancelled or completed (fire-and-forget, background work, etc.):

     (async
       (let [background (detach (async ...))]  ; won't be cancelled with parent
         ...))

   Can also be used around await*/wait* to prevent reading the parent's
   cancellation channel."
  [& body]
  `(binding [*cancellation-chan* ::detached]
     ~@body))

(defn add-cancellation-callback!
  [chan callback]
  (locking cancellation-callbacks
    (let [callbacks (or (.get cancellation-callbacks chan) (atom #{}))]
      (.put cancellation-callbacks chan callbacks)
      (swap! callbacks conj callback))))

(defn remove-cancellation-callbacks!
  [chan]
  (locking cancellation-callbacks
    (.remove cancellation-callbacks chan)))

(defn remove-cancellation-callback!
  [chan callback]
  (locking cancellation-callbacks
    (when-let [callbacks (.get cancellation-callbacks chan)]
      (swap! callbacks disj callback)
      (when (empty? @callbacks)
        (.remove cancellation-callbacks chan)))))

(defn run-cancellation-callbacks!
  [chan]
  (let [callbacks (remove-cancellation-callbacks! chan)]
    (when callbacks
      (doseq [callback @callbacks]
        (callback)))))

(defn cancelled?
  "Returns true if execution context was cancelled and thus should be
   interrupted/short-circuited, false otherwise.

   Users are expected, when inside an execution block like async, blocking or
   compute, to check using (cancelled? or check-cancelled!) as often as they can
   in case someone tried to cancel their execution, in which case they should
   interrupt/short-circuit the work as soon as they can."
  []
  (if (or (when (cancellation-chan-bound?)
            (some? (a/poll! *cancellation-chan*)))
          (.isInterrupted (Thread/currentThread)))
    true
    false))

(defn check-cancelled!
  "Throws if execution context was cancelled and thus should be
   interrupted/short-circuited, returns nil.

   Users are expected, when inside an execution block like async, blocking or
   compute, to check using (cancelled? or check-cancelled!) as often as they can
   in case someone tried to cancel their execution, in which case they should
   interrupt/short-circuit the work as soon as they can."
  []
  (when (cancelled?)
    (throw (make-interrupted-exception))))

(defn- compute-call
  "Executes f in the compute-pool, returning immediately to the calling thread.
   Returns a channel which will receive the result of calling f when completed,
   then close."
  [f]
  (let [c (a/chan 1)
        returning-to-chan (fn [bf]
                            #(try
                               (when-some [ret (bf)]
                                 (a/>!! c ret))
                               (finally (a/close! c))))]
    (->> f bound-fn* returning-to-chan (.execute compute-pool))
    c))

(defmacro compute'
  "Executes the body in the compute-pool, returning immediately to the calling
   thread. Returns a channel which will receive the result of the body when
   completed, then close."
  [& body]
  (let [compute-call- compute-call]
    `(~compute-call- (^:once fn* [] ~@body))))

(defmacro with-lock
  "Run body while holding the given ReentrantLock."
  [^ReentrantLock lock & body]
  `(do
     (.lock ~lock)
     (try
       ~@body
       (finally
         (.unlock ~lock)))))

(defn- start-async-generator!
  "Starts an async-generator producer once unless its source was already
   returned. Registers producer cancellation callbacks before recording it in
   state."
  [ch state start-fn]
  (locking state
    (when-not (or (:started? @state)
                  (:returned? @state))
      (let [producer (start-fn)]
        (add-cancellation-callback! producer #(async.impl/close! ch))
        (when-some [resume-chan (:resume-chan @state)]
          (add-cancellation-callback! producer #(a/close! resume-chan)))
        (swap! state assoc
               :started? true
               :producer producer)))))

;; Cold core.async-compatible channel returned by async-generator. Its mutable
;; metadata field m is read and written only while holding the state monitor.
(deftype AsyncGeneratorChannel
  [ch state start-fn finalized ^:unsynchronized-mutable m]
  clojure.lang.IReference
  (meta [_]
    (locking state
      m))
  (alterMeta [_ f args]
    (locking state
      (set! m (apply f m args))
      m))
  (resetMeta [_ new-meta]
    (locking state
      (set! m new-meta)
      m))

  p/Datafiable
  (datafy [_]
    (p/datafy ch))

  async.impl/Channel
  (closed? [_]
    (async.impl/closed? ch))
  (close! [_]
    (async.impl/close! ch))

  async.impl/ReadPort
  (take! [_ handler]
    (start-async-generator! ch state start-fn)
    (when (and (:resume-chan @state)
               (:needs-resume? @state))
      (swap! state assoc :needs-resume? false)
      (a/put! (:resume-chan @state) true))
    (async.impl/take! ch handler))

  async.impl/WritePort
  (put! [_ val handler]
    (async.impl/put! ch val handler)))

(defn chan?
  "Returns true if v is a core.async or async-style channel, false otherwise."
  [v]
  (or (instance? ManyToManyChannel v)
      (instance? AsyncGeneratorChannel v)))

(defn- promise-chan?
  "Returns true for core.async promise-chans, which async-style treats as
   single-result async values during producer settlement. Ordinary channels and
   async-generator channels are multi-value/source channels and are preserved."
  [v]
  (and (instance? ManyToManyChannel v)
       (instance? PromiseBuffer (.-buf ^ManyToManyChannel v))))

(defn ensure-started!
  "Starts a cold async-generator channel if needed. Idempotent."
  [^AsyncGeneratorChannel source]
  (start-async-generator! (.-ch source) (.-state source) (.-start-fn source))
  source)

(defn- cancel-channel!
  "Settles chan with cancellation value v and runs its cancellation callbacks
   when settlement succeeds. Returns whether cancellation won, or nil when the
   coerced value is not channel-like."
  [chan v]
  (let [chan (->promise-chan chan)]
    (when (chan? chan)
      (let [cancelled (settle-immediately! chan v)]
        (when cancelled
          (run-cancellation-callbacks! chan))
        cancelled))))

(defn async-return!
  "Requests early cleanup for an async-generator channel. Returns its
   finalization channel."
  [^AsyncGeneratorChannel source]
  (let [ch (.-ch source)
        state (.-state source)
        finalized (.-finalized source)]
    (locking state
      (when-not (:returned? @state)
        (swap! state assoc :returned? true)
        (async.impl/close! ch)
        (when-some [resume-chan (:resume-chan @state)]
          (a/close! resume-chan))
        (if-some [producer (:producer @state)]
          (cancel-channel! producer (make-cancellation-exception))
          (settle-immediately! finalized true))))
    finalized))

(defn source-cleanup-chan
  "Requests cleanup for an async-generator source and returns its finalization
   channel. Returns nil for sources without async-generator lifecycle support."
  [source]
  (when (instance? AsyncGeneratorChannel source)
    (async-return! source)))

(defn cancel!
  "When called on chan, tries to tell processes currently executing over the
   chan that they should interrupt and short-circuit (aka cancel) their execution
   as soon as they can, as it is no longer needed.

   The way cancellation is conveyed is by settling the return channel of async,
   blocking and compute blocks to a CancellationException, unless passed a v
   explicitly, in which case it will settle it with v.

   When called on a lifecycle-aware async-style source, such as an
   async-generator, it requests lifecycle cleanup using the same path as areturn.
   This is fire-and-forget; use areturn when you need to wait for cleanup and
   finally blocks to finish.

   That means by default a block that has its execution cancelled will return a
   CancellationException and thus awaiters and other takers of its result will
   see the exception and can handle it accordingly. If instead you want to cancel
   the block so it returns a value, pass in a v and the awaiters and takers will
   receive that value instead. You can't set nil as the cancelled value,
   attempting to do so will throw an IllegalArgumentException.

   It is up to processes inside async, blocking and compute blocks to properly
   check for cancellation on a channel."
  ([chan]
   (cancel! chan (make-cancellation-exception)))
  ([chan v]
   (when (nil? v)
     (throw (IllegalArgumentException. "Can't put nil as the cancelled value.")))
   (let [chan (->promise-chan chan)]
     (if (instance? AsyncGeneratorChannel chan)
       (do
         (async-return! chan)
         true)
       (cancel-channel! chan v)))))

(defn register-child!
  [parent child]
  (when (and (chan? parent) (chan? child) (not= parent child))
    (add-cancellation-callback! parent #(cancel! child))))

(defn- settle-coerced!
  "Settle chan with an already ->promise-chan-coerced value.

   Async-style settlement assimilates one promise-like single-result async
   value: if v is a promise-chan, take from it asynchronously and settle chan
   with the taken value; otherwise settle chan immediately. Multi-value source
   channels, including async-generator channels and ordinary raw channels, are
   preserved as values. Cancellation callbacks run only after chan is actually
   settled."
  [chan v]
  (if (promise-chan? v)
    (a/take! v
             #(when (settle-immediately! chan (->promise-chan %))
                (run-cancellation-callbacks! chan)))
    (when (settle-immediately! chan v)
      (run-cancellation-callbacks! chan))))

(defn settle!
  "Settle chan with v after coercing v through ->promise-chan.

   This is the normal producer-side settlement path. It accepts values supported
   by IntoPromiseChan, assimilates one promise-like single-result async value,
   preserves multi-value source channels, and runs cancellation callbacks after
   chan is actually settled."
  [chan v]
  (settle-coerced! chan (->promise-chan v)))

(defn settle-blocking!
  "Settle chan from a blocking/compute worker after coercing v through
   ->promise-chan.

   Like settle!, this assimilates one promise-like single-result async value,
   but waits inline on the current worker thread while still respecting the
   worker's cancellation channel. Multi-value source channels are preserved.
   Cancellation callbacks run only after chan is actually settled."
  [chan v]
  (letfn [(take-or-cancel!
            [chan']
            (if (cancellation-chan-bound?)
              (a/alt!!
                *cancellation-chan*
                ([_] (make-interrupted-exception))
                chan'
                ([v] (->promise-chan v))
                :priority true)
              (->promise-chan (a/<!! chan'))))]
    (let [v (->promise-chan v)
          v (if (promise-chan? v)
              (take-or-cancel! v)
              v)]
      (when (settle-immediately! chan v)
        (run-cancellation-callbacks! chan)))))

(defn- async'
  "Wraps body in a way that it executes in an async or blocking block with
   support for cancellation and returns a promise-chan settled with the result
   or any exception thrown."
  [body execution-type]
  (case execution-type
    :async `(let [ret# (a/promise-chan)
                  parent# (when (cancellation-chan-bound?) *cancellation-chan*)]
              (register-child! parent# ret#)
              (a/go
                (binding [*cancellation-chan* ret#]
                  (when-not (cancelled?)
                    (settle! ret#
                             (try (do ~@body)
                                  (catch Throwable t#
                                    t#))))))
              ret#)
    `(let [ret# (a/promise-chan)
           interrupter-thread# (volatile! nil)
           interrupt-lock# (ReentrantLock.)
           interrupter# (fn []
                          (with-lock interrupt-lock#
                            (when-some [^Thread t# @interrupter-thread#]
                              (.interrupt t#))))
           parent# (when (cancellation-chan-bound?) *cancellation-chan*)]
       (add-cancellation-callback! ret# interrupter#)
       (register-child! parent# ret#)
       (~(case execution-type
           :blocking (if executor-for `a/io-thread `a/thread)
           :compute `compute')
        (binding [*cancellation-chan* ret#]
          (let [value-or-error#
                (try
                  (vreset! interrupter-thread# (Thread/currentThread))
                  (when-not (cancelled?)
                    (try (do ~@body)
                         (catch Throwable t#
                           t#)))
                  (finally
                    (with-lock interrupt-lock#
                      (vreset! interrupter-thread# nil))
                    (remove-cancellation-callback! ret# interrupter#)))]
            (settle-blocking! ret# value-or-error#))))
       ret#)))

(defmacro async
  "Asynchronously execute body on the async-pool with support for cancellation,
   returning a promise-chan settled with the result or any exception thrown.

   body will run on the async-pool, so if you plan on doing something blocking
   or compute heavy, use blocking or compute instead."
  [& body]
  (async' body :async))

(defmacro blocking
  "Asynchronously execute body on the blocking-pool with support for
   cancellation, returning a promise-chan settled with the result or any
   exception thrown.

   body will run on the blocking-pool, so use this when you will be blocking or
   doing blocking io only."
  [& body]
  (async' body :blocking))

(defmacro compute
  "Asynchronously execute body on the compute-pool with support for
   cancellation, returning a promise-chan settled with the result or any
   exception thrown.

   body will run on the compute-pool, so use this when you will be doing heavy
   computation, and don't block, if you're going to block use blocking
   instead. If you're doing a very small computation, like polling another chan,
   use async instead."
  [& body]
  (async' body :compute))

(defn make-async-generator
  "Creates a cold AsyncGeneratorChannel using opts and producer-fn.

   A zero or omitted :buffer-size creates an unbuffered pull-based source;
   positive sizes create a fixed lossless buffer."
  [opts producer-fn]
  (let [buffer-size (:buffer-size opts 0)
        unbuffered? (zero? buffer-size)
        out (if (zero? buffer-size)
              (a/chan)
              (a/chan buffer-size))
        finalized (a/promise-chan)
        state (atom {:started? false
                     :returned? false
                     :resume-chan (when unbuffered?
                                    (a/chan))
                     :needs-resume? false})
        start-fn (fn [] (producer-fn out state finalized))]
    (AsyncGeneratorChannel. out state start-fn finalized nil)))

(def ^:dynamic *yield-context*
  "Binds the current async-generator output channel and state for yield."
  nil)

(defmacro async-generator
  "Creates a cold async-style channel whose body can yield many values.

   The body runs on the async-pool when the returned channel is first consumed,
   not when the channel is created. Values published with yield are delivered as
   raw channel values, and channel close means the generator is done.

   Accepts an optional options map. Currently supported:
     * :buffer-size - fixed, lossless output buffer size. Defaults to 0 for an
       unbuffered, pull-based handoff."
  [& body]
  (let [[opts body] (if (map? (first body))
                      [(first body) (next body)]
                      [{} body])]
    `(make-async-generator
      ~opts
      (fn [out# state# finalized#]
        (async
          (try
            (await
             (binding [*yield-context* {:chan out# :state state#}]
               ~@body))
            (catch Throwable t#
              (when-not (or (:returned? @state#)
                            (instance? InterruptedException t#)
                            (instance? CancellationException t#))
                (a/>! out# t#)))
            (finally
              (a/close! out#)
              (settle-immediately! finalized# true))))))))

(defmacro yield
  "Publishes one value from inside async-generator.

   The value is consumed through await before it is put on the output channel, so
   yielding a promise-chan, Future, or CompletableFuture exposes its settled
   value to downstream consumers. yield parks when the configured generator
   buffer is full and returns nil on success.

   async-generator cannot yield nil, because nil is core.async's closed-channel
   value and means the source is done."
  [v]
  `(do
     (when (nil? *yield-context*)
       (throw (IllegalStateException. "yield can only be used inside async-generator.")))
     (let [yield-chan# (:chan *yield-context*)
           yield-state# (:state *yield-context*)
           v# (await ~v)]
       (when (nil? v#)
         (throw (IllegalArgumentException. "async-generator cannot yield nil; nil means done.")))
       (if (cancellation-chan-bound?)
         (a/alt!
           *cancellation-chan*
           ([_#] (throw (make-interrupted-exception)))
           [[yield-chan# v#]]
           ([accepted?# _#]
            (when-not accepted?#
              (throw (make-interrupted-exception)))
            nil)
           :priority true)
         (when-not (a/>! yield-chan# v#)
           (throw (make-interrupted-exception))))
       (when-some [resume-chan# (:resume-chan @yield-state#)]
         (swap! yield-state# assoc :needs-resume? true)
         (if (cancellation-chan-bound?)
           (a/alt!
             *cancellation-chan*
             ([_#] (throw (make-interrupted-exception)))
             resume-chan#
             ([v#]
              (when-not v#
                (throw (make-interrupted-exception)))
              nil)
             :priority true)
           (when-not (a/<! resume-chan#)
             (throw (make-interrupted-exception))))))))

(extend-protocol IntoPromiseChan
  nil
  (->promise-chan [this] this)
  ManyToManyChannel
  (->promise-chan [this] this)
  AsyncGeneratorChannel
  (->promise-chan [this] this)
  IBlockingDeref
  (->promise-chan [this]
    (let [pc (detach
              (blocking
                (try
                  @this
                  (catch ExecutionException e
                    (throw (.getCause e))))))
          callbacks (locking cancellation-callbacks
                      (.get cancellation-callbacks pc))]
      (when callbacks
        (when (instance? Future this)
          (swap! callbacks conj (fn [] (future-cancel this)))))
      pc))
  CompletableFuture
  (->promise-chan [this]
    (let [pc (detach
              (blocking
                (try
                  @this
                  (catch ExecutionException e
                    (throw (.getCause e))))))
          callbacks (locking cancellation-callbacks
                      (.get cancellation-callbacks pc))]
      (when callbacks
        (swap! callbacks conj (fn [] (.cancel this true))))
      pc))
  Object
  (->promise-chan [this] this))

(defn- take'
  "Parking take from chan-or-value, returning the taken result or value.

   Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  [chan-or-value]
  `(let [chan# (->promise-chan ~chan-or-value)]
     (if (chan? chan#)
       (if (cancellation-chan-bound?)
         (a/alt!
           *cancellation-chan*
           ([_#] (make-interrupted-exception))
           chan#
           ([v#] (->promise-chan v#))
           :priority true)
         (->promise-chan (a/<! chan#)))
       chan#)))

(defn- <<!'
  "Wraps chan-or-value so that if chan it takes from it returning the taken
   result, else if value it returns value directly, or if chan-or-value throws it
   returns the thrown exception.

   Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  [chan-or-value]
  (let [chan-or-value-gensym (gensym 'chan-or-value)]
    `(let [~chan-or-value-gensym (try ~chan-or-value
                                      (catch Throwable t#
                                        t#))
           value-or-error# ~(take' chan-or-value-gensym)]
       value-or-error#)))

(defmacro await*
  "Parking takes from chan-or-value so that any exception is returned, and with
   async-style promise values settled by their producers.

   Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  [chan-or-value]
  (<<!' chan-or-value))

(defn wait*
  "Blocking takes from chan-or-value so that any exception is returned, and with
   async-style promise values settled by their producers.

   Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  [chan-or-value]
  (let [chan-or-value (->promise-chan chan-or-value)]
    (if (chan? chan-or-value)
      (if (cancellation-chan-bound?)
        (a/alt!!
          *cancellation-chan*
          ([_] (make-interrupted-exception))
          chan-or-value
          ([v] (->promise-chan v))
          :priority true)
        (->promise-chan (a/<!! chan-or-value)))
      chan-or-value)))

(defn- <<?'
  "Wraps chan-or-value so that if chan it takes from it returning the taken
   result, else if value it returns value directly, or if chan-or-value throws it
   re-throws exception.

   Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  [chan-or-value]
  `(let [value-or-error# (await* ~chan-or-value)]
     (if (error? value-or-error#)
       (throw value-or-error#)
       value-or-error#)))

(defn- <<??'
  "Wraps chan-or-value so that if chan it takes from it returning the taken
   result, else if value it returns value directly, or if chan-or-value throws it
   re-throws exception.

   Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  [chan-or-value]
  `(let [value-or-error# (wait* ~chan-or-value)]
     (if (error? value-or-error#)
       (throw value-or-error#)
       value-or-error#)))

(defmacro await
  "Parking takes from chan-or-value so that any exception taken is re-thrown,
   returning the value settled by the producer.

   Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  [chan-or-value]
  (<<?' chan-or-value))

(defmacro wait
  "Blocking takes from chan-or-value so that any exception taken is re-thrown,
   returning the value settled by the producer.

   Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  [chan-or-value]
  (<<??' chan-or-value))

(defn catch
  "Parking takes the settled value from chan. If value is an error of
   pred-or-type, will call error-handler with it.

   Returns a promise-chan settled with the value or the return of the
   error-handler.

   error-handler will run on the async-pool, so if you plan on doing something
   blocking or compute heavy, remember to wrap it in a blocking or compute
   respectively.

   Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  ([chan error-handler]
   (async
     (let [v (await* chan)]
       (if (error? v)
         (error-handler v)
         v))))
  ([chan pred-or-type error-handler]
   (async
     (let [v (await* chan)]
       (if (error? v)
         (cond (and (class? pred-or-type) (instance? pred-or-type v))
               (error-handler v)
               (and (ifn? pred-or-type) (pred-or-type v))
               (error-handler v)
               :else v)
         v)))))

(defn finally
  "Parking takes the settled value from chan, and calls f with it no matter if
   the value is ok? or error?.

   Returns a promise-chan settled with the taken value, and not the return of f,
   which means f is implied to be doing side-effect(s).

   f will run on the async-pool, so if you plan on doing something blocking or
   compute heavy, remember to wrap it in a blocking or compute respectively.

   Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  [chan f]
  (async
    (let [res (await* chan)]
      (f res)
      res)))

(defn then
  "Asynchronously executes f with the result of chan once available, unless chan
   results in an error, in which case f is not executed.

   Returns a promise-chan settled with the result of f or the error.

   f will run on the async-pool, so if you plan on doing something blocking or
   compute heavy, remember to wrap it in a blocking or compute respectively.

   Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  [chan f]
  (async
    (let [v (await* chan)]
      (if (error? v)
        v
        (f v)))))

(defn chain
  "Chains multiple then together starting with chan like:
     (-> chan (then f1) (then f2) (then fs) ...)

   fs will all run on the async-pool, so if you plan on doing something blocking
   or compute heavy, remember to wrap it in a blocking or compute respectively.

   Note:
    * chan can be a channel or something supported by IntoPromiseChan."
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
   blocking or compute respectively.

   Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  ([chan f]
   (async
     (let [v (await* chan)]
       (f v))))
  ([chan ok-handler error-handler]
   (async
     (let [v (await* chan)]
       (if (error? v)
         (error-handler v)
         (ok-handler v))))))

(defn sleep
  "Asynchronously sleep ms time, returns a promise-chan which settles after ms
   time."
  [ms]
  (async (a/alt!
           *cancellation-chan*
           ([_] (make-interrupted-exception))
           (a/timeout ms)
           ([v] v)
           :priority true)))

(defn defer
  "Waits ms time and then asynchronously executes value-or-fn, returning a
   promsie-chan settled with the result.

   value-or-fn will run on the async-pool, so if you plan on doing something
   blocking or compute heavy, remember to wrap it in a blocking or compute
   respectively."
  [ms value-or-fn]
  (async (a/alt!
           *cancellation-chan*
           ([_] (make-interrupted-exception))
           (a/timeout ms)
           ([v] v)
           :priority true)
         (when-not (cancelled?)
           (if (fn? value-or-fn)
             (value-or-fn)
             value-or-fn))))

(defn timeout
  "Observes one result from chan before ms time has passed and returns a
   promise-chan settled with that result. If the deadline wins, returns a
   promise-chan settled with a TimeoutException or the result of
   timed-out-value-or-fn.

   Timing out only stops timeout's own observation. It does not close, cancel,
   or lifecycle-return chan, so a many-valued channel remains usable.

   timed-out-value-or-fn will run on the async-pool, so if you plan on doing
   something blocking or compute heavy, remember to wrap it in a blocking or
   compute respectively.

   Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  ([chan ms]
   (timeout chan ms (TimeoutException. (str "Channel timed out: " ms "ms."))))
  ([chan ms timed-out-value-or-fn]
   (let [ret (a/promise-chan)
         chan (->promise-chan chan)]
     (register-child! (when (cancellation-chan-bound?) *cancellation-chan*) ret)
     (if (chan? chan)
       (a/go
         (let [timer (a/timeout ms)
               [v selected] (a/alts! [ret chan timer] :priority true)]
           (cond
             (= selected chan)
             (settle! ret v)

             (= selected timer)
             (settle! ret (try
                            (if (fn? timed-out-value-or-fn)
                              (timed-out-value-or-fn)
                              timed-out-value-or-fn)
                            (catch Throwable t
                              t))))))
       (settle-coerced! ret chan))
     ret)))

(defn- observe-until-settled!
  "Runs f with chan-or-value's result unless ret settles first. The second
   argument to f is true when the value was already coerced through
   ->promise-chan, false when it was taken from a channel. Plain values are
   already available, so they are applied synchronously."
  [ret chan-or-value f]
  (if (chan? chan-or-value)
    (a/go
      (let [[v selected] (a/alts! [ret chan-or-value] :priority true)]
        (when (= selected chan-or-value)
          (f v false))))
    (f chan-or-value true)))

(defn- settle-observed!
  [ret v coerced?]
  (if coerced?
    (settle-coerced! ret v)
    (settle! ret v)))

(defn race
  "Returns a promise-chan that settles with the first observed result from
   chans. A many-valued channel contributes one next take, and a single shared
   selection ensures losing many-valued channels are not consumed.

   Unlike any, this will also return the first error? to be returned by one of
   the chans. So if the first chan to fulfill does so with an error?, race will
   return a promise-chan settled with that error.

   Inputs are observed only; race does not close, cancel, or lifecycle-return
   them.

  Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  [chans]
  (let [ret (a/promise-chan)]
    (if (seq chans)
      (let [inputs (mapv ->promise-chan chans)
            available (some #(when-not (chan? %) [true %]) inputs)]
        (if-some [[_ v] available]
          (settle-coerced! ret v)
          (a/go
            (let [[v selected] (a/alts! (into [ret] inputs) :priority true)]
              (when-not (= selected ret)
                (settle! ret v))))))
      (settle! ret nil))
    ret))

(defn any
  "Returns a promise-chan that settles as soon as one of the chan in chans
   fulfills in ok?, with the value settled by that chan.

   Unlike race, this will ignore chans that fulfilled with an error?. So if the
   first chan to fulfill does so with an error?, any will keep waiting for
   another chan to eventually fulfill in ok?.

   If all chans fulfill in error?, returns an error containing the list of all
   the errors.

   Each many-valued channel contributes at most one next take. Inputs are
   observed only; any does not close, cancel, or lifecycle-return them.

  Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  [chans]
  (let [ret (a/promise-chan)
        errors (atom [])]
    (if (seq chans)
      (let [chans (mapv ->promise-chan chans)
            remaining (atom (count chans))]
        (doseq [chan chans]
          (observe-until-settled!
           ret
           chan
           (fn [v coerced?]
             (if (error? v)
               (do
                 (swap! errors conj v)
                 (when (zero? (swap! remaining dec))
                   (settle-coerced! ret
                                    (ex-info
                                     "All chans returned errors"
                                     {:block :any
                                      :errors @errors
                                      :type :all-errored}))))
               (settle-observed! ret v coerced?))))))
      (settle! ret nil))
    ret))

(defn all-settled
  "Concurrently observes one result from each input in chans and returns a
   promise-chan settled with a vector of the ok? and error? results in input
   order. Many-valued channels contribute one next take.

   It is typically used when you have multiple asynchronous tasks that are not
   dependent on one another to complete successfully, or you'd always like to
   know the result of each chan even when one errors.

   In comparison, the promise-chan returned by all may be more appropriate if
   the tasks are dependent on each other / if you'd like to immediately stop upon
   any of them returning an error?.

   Inputs are observed only; all-settled does not close, cancel, or
   lifecycle-return them.

  Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  [chans]
  (let [ret (a/promise-chan)]
    (try
      (if (seq chans)
        (let [chans (mapv ->promise-chan chans)
              n (count chans)
              results (atom (vec (repeat n nil)))
              remaining (atom n)]
          (doseq [[idx chan] (map-indexed vector chans)]
            (observe-until-settled!
             ret
             chan
             (fn [v _]
               (swap! results assoc idx v)
               (when (zero? (swap! remaining dec))
                 (settle-coerced! ret @results))))))
        (settle-coerced! ret []))
      (catch Throwable t
        (settle! ret t)))
    ret))

(defn all
  "Takes a seqable of chans as an input, and returns a promise-chan that settles
   after all of the given chans have fulfilled in ok?, with a vector of the taken
   ok? results of the input chans. This returned promise-chan will settle when
   all of the input's chans have fulfilled, or if the input seqable contains no
   chans (only values or empty). It settles in error? immediately upon any of the
   input chans returning an error? or non-chans throwing an error?, and will
   contain the error? of the first taken chan to return one.

   Inputs are observed concurrently and many-valued channels contribute one
   next take. All does not close, cancel, or lifecycle-return inputs when one
   errors.

  Note:
    * chan can be a channel or something supported by IntoPromiseChan."
  [chans]
  (let [ret (a/promise-chan)]
    (if (seq chans)
      (let [chans (mapv ->promise-chan chans)
            n (count chans)
            results (atom (vec (repeat n nil)))
            remaining (atom n)]
        (doseq [[idx chan] (map-indexed vector chans)]
          (observe-until-settled!
           ret
           chan
           (fn [v coerced?]
             (if (error? v)
               (settle-observed! ret v coerced?)
               (do
                 (swap! results assoc idx v)
                 (when (zero? (swap! remaining dec))
                   (settle-coerced! ret @results))))))))
      (a/close! ret))
    ret))

(defn source-items
  "Returns source as a sequence of items for async iteration.

   Nil stays empty, seqable and Iterable sources are traversed, and other values
   become a single-item sequence."
  [source]
  (cond
    (nil? source) nil
    (seqable? source) (seq source)
    (instance? Iterable source) (iterator-seq (.iterator ^Iterable source))
    :else (list source)))

(defn anext
  "Takes one raw value from channel-like source, returning a promise-chan.

   If source is a cold async-style generator, taking starts it in the current
   scope. The returned promise-chan settles to the next value, or nil when the
   source is done. Errors are represented using normal async-style semantics:
   use wait/await to throw them, or wait*/await* to receive them as values.

   anext observes borrowed plain channels without closing or cancelling them.
   Collection-like sources are not supported."
  [source]
  (let [ret (a/promise-chan)
        parent (when (cancellation-chan-bound?) *cancellation-chan*)]
    (register-child! parent ret)
    (if (chan? source)
      (do
        (when (instance? AsyncGeneratorChannel source)
          (ensure-started! source))
        (a/go
          (let [[v selected] (a/alts! [ret source] :priority true)]
            (when (= selected source)
              (settle! ret v)))))
      (settle! ret (IllegalArgumentException.
                    "anext only supports channel-like sources.")))
    ret))

(defn areturn
  "Requests early cleanup for a lifecycle-aware async-style source.

   Returns a promise-chan settling to nil after cleanup has completed. For
   borrowed plain channels, areturn is a no-op: it does not close or cancel the
   channel."
  [source]
  (detach
    (async
      (when-some [cleanup (source-cleanup-chan source)]
        (detach (await cleanup)))
      nil)))

(defn areduce
  "Asynchronously reduces source with rf and init, returning a promise-chan
   settled with the final accumulator.

   source may be channel-like or collection-like. Each element is consumed with
   await semantics, so Throwable values are thrown and values supported by
   IntoPromiseChan are awaited. If rf returns reduced, reduction stops early,
   lifecycle-aware sources are returned/cleaned up, and the promise-chan settles
   with the unwrapped reduced value.

   rf runs on the async-pool and should be quick: do not block, park, wait, or
   perform heavy compute work inside it."
  [rf init source]
  (async
    (let [source (->promise-chan source)]
      (try
        (if (chan? source)
          (loop [acc init]
            (let [v (await* source)]
              (if (nil? v)
                acc
                (let [acc' (rf acc (await v))]
                  (if (reduced? acc')
                    (do
                      (detach (await (areturn source)))
                      @acc')
                    (recur acc'))))))
          (loop [acc init xs (seq (source-items source))]
            (if-let [xs (seq xs)]
              (let [acc' (rf acc (await (first xs)))]
                (if (reduced? acc')
                  @acc'
                  (recur acc' (next xs))))
              acc)))
        (catch Throwable t
          (when (chan? source)
            (detach (await (areturn source))))
          (throw t))))))

(defn atransduce
  "Asynchronously transduces source with xf, rf, and init, returning a
   promise-chan settled with the completed reduction result.

   source may be channel-like or collection-like. Early transducer completion,
   errors, and cancellation return/clean up lifecycle-aware sources. Transducer
   and reducing steps run on the async-pool and should be quick; do not block,
   park, wait, or perform heavy compute work inside them."
  [xf rf init source]
  (async
    (let [source (->promise-chan source)
          rf (xf rf)]
      (try
        (let [acc (if (chan? source)
                    (loop [acc init]
                      (let [v (await* source)]
                        (if (nil? v)
                          acc
                          (let [acc' (rf acc (await v))]
                            (if (reduced? acc')
                              (do
                                (detach (await (areturn source)))
                                @acc')
                              (recur acc'))))))
                    (loop [acc init xs (seq (source-items source))]
                      (if-let [xs (seq xs)]
                        (let [acc' (rf acc (await (first xs)))]
                          (if (reduced? acc')
                            @acc'
                            (recur acc' (next xs))))
                        acc)))]
          (rf acc))
        (catch Throwable t
          (when (chan? source)
            (detach (await (areturn source))))
          (throw t))))))

(defn ainto
  "Asynchronously consumes source into to, returning a promise-chan settled with
   the completed collection.

   With two arguments, behaves like async into. With three arguments, applies xf
   as a transducer. source may be channel-like or collection-like, and each
   element is consumed with await semantics."
  ([to source]
   (atransduce identity conj to source))
  ([to xf source]
   (atransduce xf conj to source)))

(defn async-iteration-stop
  "Returns the internal exception used to stop async iteration normally."
  []
  (ex-info "Async iteration stopped." {:type ::async-iteration-stopped}))

(defn async-iteration-stopped?
  "Returns true when t is the internal normal-stop exception for async
   iteration."
  [t]
  (and (instance? clojure.lang.ExceptionInfo t)
       (= ::async-iteration-stopped (:type (ex-data t)))))

(defn- parse-async-for-bindings
  "Parses async for-style bindings into binding, source, and modifier groups."
  [bindings]
  (loop [bindings (seq bindings)
         groups []]
    (if-not bindings
      groups
      (let [[binding source & more] bindings
            [mods more] (loop [xs more mods []]
                          (if (keyword? (first xs))
                            (recur (nnext xs) (conj mods [(first xs) (second xs)]))
                            [mods xs]))]
        (when (nil? source)
          (throw (IllegalArgumentException. "Async binding forms require an even binding/source pair.")))
        (recur more (conj groups {:binding binding
                                  :source source
                                  :mods mods}))))))

(defn- apply-async-for-mods
  "Wraps an emitted async-iteration body with its :let, :when, and :while
   modifiers."
  [mods body]
  (reduce
   (fn [body [k expr]]
     (case k
       :let `(let ~expr ~body)
       :when `(when ~expr ~body)
       :while `(if ~expr
                 ~body
                 (throw (async-iteration-stop)))
       (throw (IllegalArgumentException.
               (str "Unsupported async iteration modifier: " k)))))
   body
   (reverse mods)))

(defn- consume-async-for-source
  "Consumes one async iteration binding source and awaits f for each item."
  [source-fn f]
  (async
    (let [source (->promise-chan (source-fn))
          channel? (chan? source)
          items (when-not channel?
                  (atom (seq (source-items source))))
          normal? (atom false)]
      (try
        (loop []
          (let [[more? item]
                (if channel?
                  (let [item (await* source)]
                    [(some? item) item])
                  (if-let [remaining (seq @items)]
                    (do
                      (swap! items next)
                      [true (first remaining)])
                    [false nil]))]
            (if more?
              (do
                (await (f (await item)))
                (recur))
              (reset! normal? true))))
        (catch Throwable t
          (when-not (async-iteration-stopped? t)
            (throw t)))
        (finally
          (when (and channel? (not @normal?))
            (detach (await (areturn source))))))
      nil)))

(defn- emit-async-for
  "Emits nested async iteration for parsed binding groups, including lifecycle
   cleanup when channel consumption ends early."
  [groups body]
  (if-let [{:keys [binding source mods]} (first groups)]
    (let [item-sym (gensym "item")
          inner (if (next groups)
                  `(await ~(emit-async-for (next groups) body))
                  `(do ~@body))
          iteration-body (apply-async-for-mods mods inner)]
      `(#'consume-async-for-source
        (fn [] ~source)
        (fn [~item-sym]
          (async
            (let [~binding ~item-sym]
              ~iteration-body)
            nil))))
    `(async
       ~@body
       nil)))

(defmacro adoseq
  "Asynchronously consumes channel-like or collection-like sources for side
   effects, returning a promise-chan that settles to nil.

   Binding forms follow Clojure doseq/for style for destructuring, nested
   bindings, :let, :when, and :while. Each element is consumed with await
   semantics. On :while short-circuit, errors, or cancellation,
   lifecycle-aware sources are returned/cleaned up; borrowed plain channels are
   only observed."
  [bindings & body]
  (emit-async-for (parse-async-for-bindings bindings) body))

(defmacro afor
  "Asynchronously comprehends over channel-like or collection-like sources,
   returning a promise-chan settled with an eager vector of body results.

   Supports the same binding and qualifier forms as adoseq. Body-returned
   reduced values are ordinary values; use :while for short-circuiting."
  [bindings & body]
  `(async
     (let [results# (atom [])]
       (await
        (adoseq ~bindings
          (swap! results# conj (do ~@body))))
       @results#)))

(defmacro ado
  "Asynchronous do. Execute expressions one after the other, awaiting the result
   of each one before moving on to the next. Results are lost to the void, same
   as clojure.core/do, so side effects are expected. Returns a promise-chan which
   settles with the result of the last expression when the entire do! is done.

   Note:
    * exprs can return a channel or something supported by IntoPromiseChan."
  [& exprs]
  `(async
     ~@(map #(list `await %) exprs)))

(defmacro alet
  "Asynchronous let. Binds result of async expressions to local binding, executing
   bindings in order one after the other.

   Note:
    * exprs can return a channel or something supported by IntoPromiseChan."
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
  "Evaluates expr and reports the time it took. Returns the original value of
   expr. If expr evaluates to a channel, observes one result before reporting.

   The timing callback is detached observation-only instrumentation. It does
   not own or manage the observed value and is not suppressed merely because
   the caller's cancellation scope completes.

   Note:
    * expr can return a channel or something supported by IntoPromiseChan."
  ([expr]
   `(time ~expr (fn [time-ms#] (prn (str "Elapsed time: " time-ms# " msecs")))))
  ([expr print-fn]
   (let [chan?- chan?]
     `(let [start# (System/nanoTime)
            prn-time-fn# (fn prn-time-fn#
                           ([~'_] (prn-time-fn#))
                           ([] (~print-fn (/ (double (- (System/nanoTime) start#)) 1000000.0))))
            ret# ~(->promise-chan expr)]
        (if (~chan?- ret#)
          (detach (handle ret# prn-time-fn#))
          (prn-time-fn#))
        ret#))))
