(ns com.xadecimal.async-style-test
  (:refer-clojure :exclude [await time])
  (:require [hyperfiddle.rcf :as rcf :refer [! % tests]]
            [com.xadecimal.async-style :as a :refer :all]))

;;;;;;;;;;;;;
;;;; all ;;;;

(do
  (rcf/set-timeout! 210)
  (tests
    "Awaits all promise-chan in given seq and returns a vector of
their results in order. Promise-chan obviously run concurrently,
so the entire operation will take as long as the longest one."
    (async
     (! (await (all [1 (defer 100 2) (defer 200 3)]))))
    % := [1 2 3])

  (rcf/set-timeout! 50)
  (tests
    "Short-circuits as soon as one promise-chan errors, returning
the error."
    (async
     (try
       (await (all [(defer 10 #(/ 1 0)) (defer 100 2) (defer 200 3)]))
       (catch Exception e
         (! e))))
    (type %) := ArithmeticException)

  (rcf/set-timeout! 1000)
  (tests
    "Can contain non-promise-chan as well."
    (async
     (! (await (all [1 2 3]))))
    % := [1 2 3]

    "But since all is not a macro, if one of them throw it throws
immediately as the argument to all get evaluated."
    (try (all [1 (defer 10 2) (/ 1 0)])
         (catch Exception e
           (! e)))
    (type %) := ArithmeticException

    "This is especially important when using some of the helper
functions instead of plain async/await, because it will
throw instead of all returning the error."
    (try
      (-> (all [1 (defer 10 2) (/ 1 0)])
          (catch (! :this-wont-work)))
      (catch Exception _
        (! :this-will)))
    % := :this-will

    "If given an empty seq, returns a promise-chan settled
to nil."
    (async
     (! (await (all []))))
    % := nil

    "If given nil, returns a promise-chan settled to nil."
    (async
     (! (await (all []))))
    % := nil))

;;;; all ;;;;
;;;;;;;;;;;;;


;;;;;;;;;;;;;;
;;;; alet ;;;;

(do
  (rcf/set-timeout! 1000)
  (tests
   "Like Clojure's let, except each binding is awaited."
   (com.xadecimal.async-style/alet
    [a (defer 100 1)
     b (defer 100 2)
     c (defer 100 3)]
    (! (+ a b c)))
   % := 6)

  (rcf/set-timeout! 200)
  (tests
   "Like Clojure's let, this is not parallel, the first binding is
awaited, then the second, then the third, etc."
   (com.xadecimal.async-style/alet
    [a (defer 100 1)
     b (defer 100 2)
     c (defer 100 3)]
    (! (+ a b c)))
   % := ::rcf/timeout)

  (rcf/set-timeout! 1000)
  (tests
   "Let bindings can depend on previous ones."
   (com.xadecimal.async-style/alet
    [a 1
     b (defer 100 (+ a a))
     c (inc b)]
    (! c))
   % := 3))

;;;; alet ;;;;
;;;;;;;;;;;;;;


;;;;;;;;;;;;;;
;;;; race ;;;;

(rcf/tests
  "Race returns the first chan to fulfill."
  (-> (race [(defer 100 1) (defer 100 2) (async 3)])
      (handle !))
  % := 3

  "If the first chan to fulfill does so with an error?, it still
   wins the race and the error gets returned in the promise-chan."
  (-> (race [(async (throw (ex-info "error" {})))
             (defer 100 :ok)])
      (handle !))
  (error? %) := true

  "Passing an empty seq will return a closed promise-chan, which in
   turn returns nil when taken from."
  (-> (race [])
      (handle !))
  % := nil

  "Passing nil will return a closed promise-chan, which in
   turn returns nil when taken from."
  (-> (race nil)
      (handle !))
  % := nil

  "chans can also contain values which will be treated as an already
   settled chan which returns itself."
  (-> (race [1 (defer 100 2)])
      (handle !))
  % := 1

  "When passing values, they are still racing asynchronously against
   each other, and the result order is non-deterministic and should
   not be depended on."
  (every?
   #{1}
   (doall
    (for [i (range 3000)]
      (-> (race [1 2 3 4 5 6 7 8 9 (async 10)])
          (<<??))))) := false

  "Race cancels all other chans once one of them fulfills."
  (-> (race [(blocking
              (Thread/sleep 100)
              (when (cancelled?)
                (! :cancelled)))
             (blocking
              (Thread/sleep 100)
              (when (cancelled?)
                (! :cancelled)))
             (blocking
              (Thread/sleep 100)
              (when (cancelled?)
                (! :cancelled)))
             (async :done)])
      (handle !))
  % := :done
  % := :cancelled
  % := :cancelled
  % := :cancelled

  "Even if the first one to fulfill did so with an error, others are
   still cancelled."
  (-> (race [(blocking
              (Thread/sleep 100)
              (when (cancelled?)
                (! :cancelled)))
             (blocking
              (Thread/sleep 100)
              (when (cancelled?)
                (! :cancelled)))
             (blocking
              (Thread/sleep 100)
              (when (cancelled?)
                (! :cancelled)))
             (async (throw (ex-info "error" {})))])
      (handle !))
  (error? %) := true
  % := :cancelled
  % := :cancelled
  % := :cancelled)

;;;; race ;;;;
;;;;;;;;;;;;;;


;;;;;;;;;;;;;
;;;; any ;;;;

(rcf/tests
  "Any returns the first chan to fulfill with an ok?."
  (-> (any [(async (throw (ex-info "error" {}))) (defer 100 2) (defer 50 3)])
      (handle !))
  % := 3

  "As we saw, chans that fulfill in error? are ignored, so when all chans
   fulfill in error?, any returns a promise-chan settled with an error of
   its own. This error will contain the list of all errors taken from all
   chans and its ex-data will have a key :type with value :all-errored."
  (-> (any [(async (throw (ex-info "error" {})))
            (async (throw (ex-info "error" {})))
            (async (throw (ex-info "error" {})))])
      (handle !))
  (let [ret %]
    (error? ret) := true
    (:type (ex-data ret)) := :all-errored)

  "Passing an empty seq will return a closed promise-chan, which in
   turn returns nil when taken from."
  (-> (any [])
      (handle !))
  % := nil

  "Passing nil will return a closed promise-chan, which in
   turn returns nil when taken from."
  (-> (any nil)
      (handle !))
  % := nil

  "chans can also contain values which will be treated as an already
   settled chan which returns itself."
  (-> (any [1 (defer 100 2)])
      (handle !))
  % := 1

  "When passing values, they are still racing asynchronously against
   each other, and the result order is non-deterministic and should
   not be depended on."
  (every?
   #{1}
   (doall
    (for [i (range 3000)]
      (-> (any [1 2 3 4 5 6 7 8 9 (async 10)])
          (<<??))))) := false

  "Any cancels all other chans once one of them fulfills in ok?."
  (-> (any [(blocking
             (Thread/sleep 100)
             (when (cancelled?)
               (! :cancelled)))
            (blocking
             (Thread/sleep 100)
             (when (cancelled?)
               (! :cancelled)))
            (blocking
             (Thread/sleep 100)
             (when (cancelled?)
               (! :cancelled)))
            (defer 30 :done)
            (async (! :throwed) (throw (ex-info "" {})))])
      (handle !))
  % := :throwed
  % := :done
  % := :cancelled
  % := :cancelled
  % := :cancelled

  "If the first one to fulfill did so with an error, others are
   not cancelled."
  (-> (any [(blocking
             (Thread/sleep 30)
             (if (cancelled?)
               (! :cancelled)
               (! :not-cancelled)))
            (blocking
             (Thread/sleep 30)
             (if (cancelled?)
               (! :cancelled)
               (! :not-cancelled)))
            (async (throw (ex-info "error" {})))])
      (handle !))
  % := :not-cancelled
  % := :not-cancelled
  % := :not-cancelled)

;;;; any ;;;;
;;;;;;;;;;;;;
