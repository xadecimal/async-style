(ns com.xadecimal.async-style
  (:refer-clojure :exclude [await resolve promise])
  (:require [clojure.core.async :as a])
  (:import [clojure.core.async.impl.channels ManyToManyChannel]
           [clojure.core.async.impl.buffers PromiseBuffer]
           [java.util Collections]))

;; TODO: support multiple catch clause in implicit-try
;; TODO: add support for cancellation, and make race and any and all auto-cancel
;; TODO: add support for heavy CPU compute operations, and blocking IO operations
;; TODO: Add ClojureScript support
;; TODO: Check how it works with clojure.test and cljs.test, there is an async test function in cljs, but how would it work in Clojure?

(defn implicit-try
  [body]
  (let [try' (take-while #(or (not (seqable? %)) (not (#{'catch 'finally} (first %)))) body)
        rem (drop-while #(or (not (seqable? %)) (not (#{'catch 'finally} (first %)))) body)
        catch (when (and (seqable? (first rem)) (= 'catch (ffirst rem))) (first rem))
        finally (or (when (and (seqable? (first rem)) (= 'finally (ffirst rem))) (first rem))
                    (when (and (seqable? (second rem)) (= 'finally (-> rem second first))) (second rem)))]
    (when (not= `(~@try' ~@(when catch [catch]) ~@(when finally [finally])) body)
      (throw (ex-info "Bad syntax, form must either not have a catch and finally block, or it must have end with both a catch followed by a finally block in that order, or it must end with a single catch block, or it must end with a single finally block." {})))
    `(~@(if (not (or catch finally))
          body
          [`(try
              ~@try'
              ~@(when catch
                  [catch])
              ~@(when finally
                  [finally]))]))))

(defn resolve
  [chan v]
  (if (nil? v)
    (a/close! chan)
    (do
      (a/put! chan v)
      (a/close! chan))))

(defmacro go-async
  [& body]
  `(let [ret# (a/promise-chan)]
     (a/go
       (let [res# (try ~@(implicit-try body)
                       (catch Throwable t#
                         t#))]
         (resolve ret# res#)))
     ret#))

(defn <?'
  [chan-or-value]
  `(let [chan-or-value# (try ~chan-or-value
                             (catch Throwable t#
                               t#))
         value-or-error# (if (chan? chan-or-value#)
                           (a/<! chan-or-value#)
                           chan-or-value#)]
     (if (error? value-or-error#)
       (throw value-or-error#)
       value-or-error#)))

(defmacro <?
  [chan-or-value & body]
  (first (implicit-try (cons (<?' chan-or-value) body))))

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

(defn error?
  [v]
  (instance? Throwable v))

(defn chan?
  [v]
  (instance? ManyToManyChannel v))

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
         (let [res# (try ~@(implicit-try body)
                         (catch Throwable t#
                           (ex-async
                            :msg ~error-msg
                            :block :async
                            :form ~form-str
                            :form-env form-env#
                            :line ~line
                            :column ~column
                            :cause t#)))]
           (resolve ret# res#)))
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
           chan-or-value# (try ~chan-or-value
                               (catch Throwable t#
                                 t#))
           value-or-error# (if (chan? chan-or-value#)
                             (a/<! chan-or-value#)
                             chan-or-value#)]
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

(defn sleep
  [ms]
  (go-async (<? (a/timeout ms))))

(defn timeout
  [ms value-or-fn & args]
  (go-async (<? (a/timeout ms)) (if (fn? value-or-fn)
                                  (apply value-or-fn args)
                                  value-or-fn)))

(defn race
  [chans]
  (let [ret (a/promise-chan)]
    (if (seq chans)
      (doseq [chan chans]
        (a/go
          (let [res (try
                      (<? chan)
                      (catch Throwable t
                        t))]
            (resolve ret res))))
      (a/close! ret))
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
                    (try
                      (resolve ret (<? chan))
                      (catch Throwable t
                        t)))))
        (a/go
          (let [errors (a/<! (a/map vector @attempt-chans))]
            (when (every? error? errors)
              (resolve ret (ex-info
                            "All chans returned errors"
                            {:block :any
                             :errors errors}))))))
      (a/close! ret))
    ret))

(defn all-settled
  [chans]
  (go-async
   (loop [res [] chan (first chans) chans (next chans)]
     (if chan
       (recur (conj res (try (<? chan)
                             (catch Throwable t
                               t)))
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
                    (try
                      (<? chan)
                      (catch Throwable t
                        (resolve ret t)
                        t)))))
        (a/go
          (let [results (a/<! (a/map vector @res-chans))]
            (when-not (some error? results)
              (resolve ret results)))))
      (a/close! ret))
    ret))

(defn catch
  ([chan ex-handler]
   (go-async
    (<? chan)
    (catch Throwable t
      (ex-handler t))))
  ([chan pred-or-type ex-handler]
   (go-async
    (<? chan)
    (catch Throwable t
      (cond (and (class? pred-or-type) (instance? pred-or-type t))
            (ex-handler t)
            (and (ifn? pred-or-type) (pred-or-type t))
            (ex-handler t)
            :else t)))))

;; TODO: better way to write finally?
(defn finally
  [chan f]
  (go-async
   (let [res (a/<! chan)]
     (try
       res
       (finally
         (if (error? res)
           (f nil res)
           (f res nil)))))))

(defn then
  [chan f]
  (go-async
   (f (<? chan))))

(defn chain
  [chan & fs]
  (reduce
   (fn [chan f] (then chan f))
   chan fs))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Promise creation a la Promesa

;; TODO: Not sure I want these included in async-style

(defn promise?
  [v]
  (and (chan? v)
       (instance? PromiseBuffer (.buf v))))

(defn promise
  [v]
  (doto (a/promise-chan)
    (resolve v)))

(defn resolved
  [v]
  (promise v))

(defn rejected
  [v]
  (promise v))

(defn create
  [f]
  (let [ret (a/promise-chan)]
    (let [resolve' #(resolve ret %)
          reject' #(resolve ret %)]
      (try (f resolve' reject')
           (catch Throwable t
             (reject' t))))
    ret))

(defn deferred
  []
  (a/promise-chan))

(defn resolve!
  ([p]
   (resolve! p nil)
   p)
  ([p v]
   (resolve p v)
   p))

(defmacro do!
  [& exprs]
  `(async
    ~@(map #(do (println (meta %)) (list `await %)) exprs)))

(-> (async (await (print 1))
           (await (print 2))
           (await (timeout 1000 #(print "OUTO")))
           (await (println))
           (await (/ 1 0))
           (await 30))
    (then (partial println "success:"))
    (catch (comp clojure.pprint/pprint ex-details)))

;; TODO: Debug why the ex-details above doesn't show the await specific error

;;;; Promise creation a la Promesa
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn foo
  [n]
  (async
   (/ 1 n)))

(-> (async (await (foo 10)))
    (catch ArithmeticException (constantly "arithmo"))
    (catch clojure.lang.ExceptionInfo (constantly "excpetoinfodo"))
    (chain inc inc inc)
    (catch Exception identity)
    (finally (fn [a b] (println [a b]))))

#_(let [a (timeout 1000 (/ 1 0))
        b (timeout 2000 2)]
    (-> (all [a b])
        (catch (constantly 1000000))
        (then println)))

#_(a/go
    (-> (all [(async :first-promise) (async (/ 1 0))])
        (await)
        (as-> [first-result second-result]
            (println (str first-result ", " second-result)))))

#_(a/go
    (-> (await (any [(timeout 2000 2) (timeout 1000 1)]))
        (println)))
