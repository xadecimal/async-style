(ns com.xadecimal.async-style.protocols)

(defprotocol IntoPromiseChan
  (->promise-chan [this]
    "Coerce to a promise-chan if possible, otherwise is just a pass-through.

     Currently supports:
       * Future
       * CompletableFuture
       * IBlockingDeref"))
