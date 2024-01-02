(ns com.xadecimal.async-style-test-utils
  "Utils that help write tests for async behavior.")

(def ^:dynamic *q* (atom []))

(defn q!
  "Enqueue x into the bound queue *q*. Use it in async tests, to queue up values
   you want to assert later in the test."
  [x]
  (swap! *q* conj x)
  x)

(defn dq!
  "Dequeue from the bound queue *q*. Waits timeout millisecond for a value to
  show up in the queue if none are currently present. Returns :timeout if no
  value showed up in the queue after waiting timeout ms time. Use it in async
  tests to grab queued with q! values in order to assert that all the async
  code queued what you expect and in the right amount of time."
  ([]
   (dq! 1000))
  ([timeout]
   (let [start-time (System/currentTimeMillis)]
     (loop []
       (let [dequeued-element (atom nil)]
         (swap! *q*
                (fn [q]
                  (if (empty? q)
                    (do (reset! dequeued-element ::empty)
                        q)
                    (do (reset! dequeued-element (first q))
                        (subvec q 1)))))
         (if (= @dequeued-element ::empty)
           (if (< (System/currentTimeMillis) (+ start-time timeout))
             (recur)
             :timeout)
           @dequeued-element))))))

(defmacro testa
  "Like clojure.test's testing macro, but also binds *q* to a new queue to be
  used for just the test inside the body of testa. Use it instead of testing
  for async tests."
  [& body]
  `(binding [*q* (atom [])]
     ~@body))
