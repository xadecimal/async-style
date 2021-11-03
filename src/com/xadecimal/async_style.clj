(ns com.xadecimal.async-style
  (:refer-clojure :exclude [await resolve])
  (:require [clj-async-profiler.core :as prof]
            [clj-java-decompiler.core :as decomp]
            [clojure.core.async :as a]
            [clojure.walk :as walk]
            [criterium.core :as cr]
            [hyperfiddle.rcf :as rcf :refer [! % tests]])
  (:import clojure.core.async.impl.channels.ManyToManyChannel
           java.util.Collections
           [java.util.concurrent CancellationException TimeoutException]))

;; TODO: add full test suite
;; TODO: add support for heavy CPU compute operations, and blocking IO operations
;; TODO: Add ClojureScript support
;; TODO: Check how it works with clojure.test and cljs.test, there is an async test function in cljs, but how would it work in Clojure?

(defn implicit-try
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

(defn resolve
  [chan v]
  (if (nil? v)
    (do
      (a/close! chan)
      true)
    (let [ret (a/offer! chan v)]
      (a/close! chan)
      ret)))

(defn error?
  [v]
  (instance? Throwable v))

(defn ok?
  [v]
  (not (error? v)))

(defn chan?
  [v]
  (instance? ManyToManyChannel v))

(def ^:dynamic *cancellation-chan*)

(defn cancelled-val?
  [v]
  (or (instance? CancellationException v)
      (reduced? v)))

(defn cancelled?
  []
  (if-let [v (a/poll! *cancellation-chan*)]
    (cancelled-val? v)
    false))

(defn cancel
  ([chan]
   (when (chan? chan)
     (resolve chan (CancellationException. "Operation was cancelled."))))
  ([chan v]
   (when (chan? chan)
     (resolve chan (reduced v)))))

(defmacro go-async
  [& body]
  `(let [ret# (a/promise-chan)]
     (a/go
       (binding [*cancellation-chan* ret#]
         (when-not (cancelled?)
           (resolve ret#
                    (try ~@(implicit-try body)
                         (catch Throwable t#
                           t#))))))
     ret#))

(defn join'
  [chan]
  `(loop [res# (a/<! ~chan)]
     (if (chan? res#)
       (recur (a/<! res#))
       res#)))

(defn <<!'
  [chan-or-value]
  (let [chan-or-value-gensym (gensym 'chan-or-value)]
    `(let [~chan-or-value-gensym (try ~chan-or-value
                                      (catch Throwable t#
                                        t#))
           value-or-error# (if (chan? ~chan-or-value-gensym)
                             ~(join' chan-or-value-gensym)
                             ~chan-or-value-gensym)]
       value-or-error#)))

(defmacro <<!
  [chan-or-value]
  (<<!' chan-or-value))

(defn <<!!
  [chan-or-value]
  (if (chan? chan-or-value)
    (loop [res (a/<!! chan-or-value)]
      (if (chan? res)
        (recur (a/<!! res))
        res))
    chan-or-value))

(defn <<?'
  [chan-or-value]
  `(let [value-or-error# (<<! ~chan-or-value)]
     (if (error? value-or-error#)
       (throw value-or-error#)
       value-or-error#)))

(defmacro <<?
  [chan-or-value & body]
  (first (implicit-try (cons (<<?' chan-or-value) body))))

(defn <<??'
  [chan-or-value]
  `(let [value-or-error# (<<!! ~chan-or-value)]
     (if (error? value-or-error#)
       (throw value-or-error#)
       value-or-error#)))

(defmacro <<??
  [chan-or-value & body]
  (first (implicit-try (cons (<<??' chan-or-value) body))))

(defprotocol AsyncExceptionDetails
  (register-ex-details [ex details])
  (get-ex-details [ex]))

(let [ex-details-registry (Collections/synchronizedMap
                           (java.util.WeakHashMap.))]
  (defn register-ex-details-impl
    [ex details]
    (.put ex-details-registry ex details)
    nil)
  (defn get-ex-details-impl
    [ex]
    (.get ex-details-registry ex)))

(defn ex-details
  [ex]
  (when (satisfies? AsyncExceptionDetails ex)
    (get-ex-details ex)))

(defn ex-async
  [& {:keys [msg block form form-env line column cause]}]
  (locking cause
    (when-not (satisfies? AsyncExceptionDetails cause)
      (extend (class cause)
        AsyncExceptionDetails
        {:register-ex-details register-ex-details-impl
         :get-ex-details get-ex-details-impl}))
    (let [details (get-ex-details cause)
          new-details {:type ::ex-details
                       :block block
                       :msg msg
                       :source *source-path*
                       :file *file*
                       :form form
                       :form-env form-env
                       :line line
                       :column column
                       :ex-message (ex-message cause)
                       :ex-data (ex-data cause)
                       :ex-type (type cause)}]
      (if-not details
        (register-ex-details cause [new-details])
        (register-ex-details cause (conj details new-details)))))
  cause)

(defn clean-env-keys
  [env]
  (->> (keys env)
       (map str)
       (remove #(re-find #"__" %))
       (remove #(re-find #"state_" %))
       (remove #(re-find #"inst_" %))
       (map symbol)))

(defn async'
  [form env body]
  (let [error-msg "Error in async block"
        form-str (pr-str form)
        form-meta (meta form)
        clean-env-k (clean-env-keys env)
        line (:line form-meta)
        column (:column form-meta)]
    `(let [form-env# ~(zipmap (mapv #(list 'quote %) clean-env-k) clean-env-k)
           ret# (a/promise-chan)]
       (a/go
         (binding [*cancellation-chan* ret#]
           (when-not (cancelled?)
             (resolve ret#
                      (try ~@(implicit-try body)
                           (catch Throwable t#
                             (ex-async
                              :msg ~error-msg
                              :block :async
                              :form ~form-str
                              :form-env form-env#
                              :line ~line
                              :column ~column
                              :cause t#)))))))
       ret#)))

(defmacro async
  [& body]
  (async' &form &env body))

(defn await'
  [form env chan-or-value]
  (let [error-msg "Error in await block"
        form-str (pr-str form)
        form-meta (meta form)
        clean-env-k (clean-env-keys env)
        line (:line form-meta)
        column (:column form-meta)]
    `(let [form-env# ~(zipmap (mapv #(list 'quote %) clean-env-k) clean-env-k)
           value-or-error# (<<! ~chan-or-value)]
       (if (error? value-or-error#)
         (throw
          (ex-async
           :msg ~error-msg
           :block :await
           :form ~form-str
           :form-env form-env#
           :line ~line
           :column ~column
           :cause value-or-error#))
         value-or-error#))))

(defmacro await
  [chan-or-value & body]
  (first (implicit-try (cons (await' &form &env chan-or-value) body))))

(defn catch
  ([chan error-handler]
   (go-async
    (let [v (<<! chan)]
      (if (error? v)
        (error-handler v)
        v))))
  ([chan pred-or-type error-handler]
   (go-async
    (let [v (<<! chan)]
      (if (error? v)
        (cond (and (class? pred-or-type) (instance? pred-or-type v))
              (error-handler v)
              (and (ifn? pred-or-type) (pred-or-type v))
              (error-handler v)
              :else v)
        v)))))

(defn finally
  [chan f]
  (go-async
   (let [res (<<! chan)]
     (f res)
     res)))

(defn then
  [chan f]
  (go-async
   (let [v (<<! chan)]
     (if (error? v)
       v
       (f v)))))

(defn chain
  [chan & fs]
  (reduce
   (fn [chan f] (then chan f))
   chan fs))

(defn handle
  ([chan f]
   (go-async
    (let [v (<<! chan)]
      (f v))))
  ([chan ok-handler error-handler]
   (go-async
    (let [v (<<! chan)]
      (if (error? v)
        (error-handler v)
        (ok-handler v))))))

(defn sleep
  [ms]
  (go-async (a/<! (a/timeout ms))))

(defn defer
  ;; TODO: Improve error details message for defer
  [ms value-or-fn]
  (go-async (a/<! (a/timeout ms))
            (when-not (cancelled?)
              (if (fn? value-or-fn)
                (value-or-fn)
                value-or-fn))))

(defn timeout
  ;; TODO: Improve error details message for timeout
  ([chan ms]
   (timeout chan ms (TimeoutException. (str "Channel timed out: " ms "ms."))))
  ([chan ms timed-out-value-or-fn]
   (go-async (let [deferred (defer ms ::timed-out)
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
  [chans]
  (let [ret (a/promise-chan)]
    (if (seq chans)
      (doseq [chan chans]
        (a/go
          (let [res (<<! chan)]
            (and (resolve ret res)
                 (run! #(when-not (= chan %) (cancel %)) chans)))))
      (resolve ret nil))
    ret))

(defn any
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
                        (and (resolve ret v)
                             (run! #(when-not (= chan %) (cancel %)) chans)))))))
        (a/go
          (let [errors (a/<! (a/map vector @attempt-chans))]
            (when (every? error? errors)
              (resolve ret (ex-info
                            "All chans returned errors"
                            {:block :any
                             :errors errors}))))))
      (resolve ret nil))
    ret))

(defn all-settled
  [chans]
  (go-async
   (loop [res [] chan (first chans) chans (next chans)]
     (if chan
       (recur (conj res (<<! chan))
              (first chans)
              (next chans))
       res))))

(defn all
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
                        (do (and (resolve ret v)
                                 (run! #(when-not (= chan %) (cancel %)) chans))
                            v)
                        v)))))
        (a/go
          (let [results (a/<! (a/map vector @res-chans))]
            (when-not (some error? results)
              (resolve ret results)))))
      (a/close! ret))
    ret))

(do
  (rcf/set-timeout! 210)
  (tests
    "Awaits all promise-chan in given seq and returns a vector of
their results in order. Promise-chan obviously run concurrently,
so the entire operation will take as long as the longest one."
    (async
     (! (await (all [1 (defer 100 2) (defer 200 3)]))))
    % := [1 2 3])

  (rcf/set-timeout! 20)
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

    "If given an empty seq, returns a promise-chan resolved
to nil."
    (async
     (! (await (all []))))
    % := nil

    "If given nil, returns a promise-chan resolved to nil."
    (async
     (! (await (all []))))
    % := nil))

(defmacro do!
  ;;TODO: Improve the error reporting with ex-details of do!
  [& exprs]
  `(async
    ~@(map #(list `await %) exprs)))

(defmacro alet
  ;;TODO: Improve the error reporting with ex-details of alet
  [bindings & exprs]
  `(async
    (clojure.core/let
        [~@(mapcat
            (fn [[sym val]] [`~sym `(await ~val)])
            (partition 2 bindings))]
      ~@exprs)))

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

(defmacro clet
  [bindings & exprs]
  ;;TODO: Improve the error reporting with ex-details of clet
  (clojure.core/let [[new-bindings prior-syms-replace-map]
                     (-> (reduce
                          (fn [[acc prior-syms-replace-map] [sym val]]
                            [(conj acc
                                   [`~sym `(async ~(walk/postwalk-replace
                                                    prior-syms-replace-map
                                                    val))])
                             (assoc prior-syms-replace-map sym `(await ~sym))])
                          [[] {}]
                          (partition 2 bindings)))]
    `(clojure.core/let
         [~@(apply concat new-bindings)]
       (async
        ~@(walk/postwalk-replace
           prior-syms-replace-map
           exprs)))))


#_(<<??
   (let [run-count (atom 0)
         chans (for [i (range 100)]
                 (async (do (swap! run-count inc) i)))]
     (mapv #(cancel % :cancel) (drop 1 chans))
     (let [res (race chans)]
       (Thread/sleep 1100)
       (println "Run-count: " @run-count)
       res)))

#_(let [a (defer 110 #(do (println :a) :a))
        b (defer 100 #(do (println :b) :b))
        c (defer 80 #(do (println :c) (/ 1 0)))]
    #_(defer 60 #(cancel b :foo))
    (<<?? (all [a b c])))
