(ns fluree.db.util.async
  (:require
    [fluree.db.util.core :refer [try* catch* exception?]]
    #?(:clj  [clojure.core.async :refer [go <!] :as async]
       :cljs [cljs.core.async :refer [go <!] :as async])
    #?(:clj [clojure.core.async.impl.ioc-macros :as ioc])
    #?(:clj [clojure.core.async.impl.dispatch :as dispatch]))
  #?(:cljs (:require-macros [fluree.db.util.async :refer [<?]])))

;; some macros for working with core async

#?(:clj
   (defn cljs-env?
     "Take the &env from a macro, and tell whether we are expanding into cljs."
     [env]
     (boolean (:ns env))))

#?(:clj
   (defmacro if-cljs
     "Return then if we are generating cljs code and else for Clojure code.
      https://groups.google.com/d/msg/clojurescript/iBY5HaQda4A/w1lAQi9_AwsJ"
     [then else]
     (if (cljs-env? &env) then else)))

(defn throw-err
  [e]
  (when (exception? e)
    (throw e))
  e)

#?(:clj
   (defmacro <?
     "Like <! but throws errors."
     [ch]
     `(if-cljs
        (throw-err (cljs.core.async/<! ~ch))
        (throw-err (clojure.core.async/<! ~ch)))))

#?(:clj
   (defmacro <??
     "Like <!! but throws errors. Only works for Java platform - no JavaScript."
     [ch]
     `(throw-err (clojure.core.async/<!! ~ch))))

#?(:clj
   (defmacro go-try
     "Like go but catches the first thrown error and returns it."
     [& body]
     `(if-cljs
        (cljs.core.async/go
          (try
            ~@body
            (catch js/Error e# e#)))
        (clojure.core.async/go
          (try
            ~@body
            (catch Throwable t# t#))))))

(defn throw-if-exception
  "Wraps errors in a new ex-info, passing though ex-data if any, and throws.
  The wrapping is done to maintain a full stack trace when jumping between
  multiple contexts."
  [x]
  (if (exception? x)
    (throw (ex-info #?(:clj (or (.getMessage x) (str x)) :cljs (str x))
                    (or (ex-data x) {})
                    x))
    x))

(defn merge-into?
  "Takes a sequence of single-value chans and returns the conjoined into collection.
  Realizes entire channel sequence first, and if an error value exists returns just the exception."
  [coll chs]
  (async/go
    (try*
      (loop [[c & r] chs
             acc coll]
        (if-not c
          acc
          (recur r (conj acc (<? c)))))
      (catch* e
              e))))

(defn into?
  "Like async/into, but checks each item for an error response and returns exception
  onto the response channel insted of results if thee is one."
  [coll chan]
  (async/go
    (try*
      (loop [acc coll]
        (if-some [v (<? chan)]
          (recur (conj acc v))
          acc))
      (catch* e
              e))))

(defn channel?
  "Returns true if core async channel."
  [x]
  #?(:clj  (satisfies? clojure.core.async.impl.protocols/Channel x)
     :cljs (satisfies? cljs.core.async.impl.protocols/Channel x)))
