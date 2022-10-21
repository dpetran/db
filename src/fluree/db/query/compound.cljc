(ns fluree.db.query.compound
  (:require [clojure.set :as set]
            [fluree.db.query.range :as query-range]
            [clojure.core.async :as async]
            #?(:clj [fluree.db.full-text :as full-text])
            [fluree.db.time-travel :as time-travel]
            [fluree.db.util.async :refer [<? go-try merge-into?]]
            [fluree.db.util.core :as util]
            [fluree.db.flake :as flake]
            [fluree.db.query.analytical-wikidata :as wikidata]
            [fluree.db.query.analytical-filter :as filter]
            [fluree.db.query.union :as union]
            [clojure.string :as str]
            [fluree.db.util.log :as log :include-macros true]
            #?(:cljs [cljs.reader])
            [fluree.db.dbproto :as dbproto]
            [fluree.db.query.analytical-parse :as parse]))

#?(:clj (set! *warn-on-reflection* true))

(defn query-range-opts
  [idx t s p o]
  (let [start-flake (flake/create s p o nil nil nil util/min-integer)
        end-flake   (flake/create s p o nil nil nil util/max-integer)]
    {:idx         idx
     :from-t      t
     :to-t        t
     :start-test  >=
     :start-flake start-flake
     :end-test    <=
     :end-flake   end-flake
     :object-fn   nil}))


(defn next-chunk-s
  [{:keys [conn] :as db} error-ch next-in {:keys [in-n] :as s} p idx t flake-x-form passthrough-fn]
  (let [out-ch   (async/chan)
        idx-root (get db idx)
        novelty  (get-in db [:novelty idx])]
    (async/go
      (loop [[in-item & r] next-in]
        (if in-item
          (let [pass-vals (when passthrough-fn
                            (passthrough-fn in-item))
                sid   (nth in-item in-n)
                opts  (query-range-opts idx t sid p nil)
                in-ch (query-range/resolve-flake-slices conn idx-root novelty error-ch opts)]
            ;; pull all subject results off chan, push on out-ch
            (loop []
              (when-let [next-chunk (async/<! in-ch)]
                (let [result (cond->> (sequence flake-x-form next-chunk)
                                     pass-vals (map #(concat % pass-vals)))]
                  (async/>! out-ch result)
                  (recur))))
            (recur r))
          (async/close! out-ch))))
    out-ch))


(defn get-chan
  [db prev-chan error-ch clause t]
  (let [out-ch (async/chan 2)
        {:keys [s p o idx flake-x-form passthrough-fn]} clause
        {s-var :variable, s-in-n :in-n} s
        {o-var :variable, o-in-n :in-n} o]
    (async/go
      (loop []
        (if-let [next-in (async/<! prev-chan)]
          (let []
            (if s-in-n
              (let [s-vals-chan (next-chunk-s db error-ch next-in s p idx t flake-x-form passthrough-fn)]
                (loop []
                  (when-let [next-s (async/<! s-vals-chan)]
                    (async/>! out-ch next-s)
                    (recur)))))
            (recur))
          (async/close! out-ch))))
    out-ch))


(defmulti get-clause-res (fn [_ _ {:keys [type] :as _clause} _ _ _ _ _]
                           type))

(defmethod get-clause-res :tuple
  [{:keys [conn] :as db} prev-chan clause t vars fuel max-fuel error-ch]
  (let [out-ch      (async/chan 2)
        {:keys [s p o idx flake-x-form]} clause
        {s-var :variable} s
        {o-var :variable} o
        s*          (or (:value s)
                        (get vars s-var))
        o*          (or (:value o)
                        (get vars o-var))
        start-flake (flake/create s* p o* nil nil nil util/min-integer)
        end-flake   (flake/create s* p o* nil nil nil util/max-integer)
        opts        {:idx         idx
                     :from-t      t
                     :to-t        t
                     :start-test  >=
                     :start-flake start-flake
                     :end-test    <=
                     :end-flake   end-flake
                     :object-fn   nil}
        idx-root    (get db idx)
        novelty     (get-in db [:novelty idx])
        range-ch    (query-range/resolve-flake-slices conn idx-root novelty error-ch opts)]
    (async/go
      (loop []
        (let [next-res (async/<! range-ch)]
          (if next-res
            (let [next-out (sequence flake-x-form next-res)]
              (async/>! out-ch next-out)
              (recur))
            (async/close! out-ch)))))
    out-ch))

(defn resolve-where-clause
  [{:keys [t] :as db} {:keys [where vars] :as _parsed-query} error-ch fuel max-fuel]
  (let [initial-chan (get-clause-res db nil (first where) t vars fuel max-fuel error-ch)]
    (loop [[clause & r] (rest where)
           prev-chan initial-chan]
      ;; TODO - get 't' from query!
      (if clause
        (let [out-chan (get-chan db prev-chan error-ch clause t)]
          (recur r out-chan))
        prev-chan))))

(defn order-results
  "Ordering must first consume all results and then sort."
  [results-ch error-ch fuel max-fuel {:keys [comparator] :as _order-by}]
  (async/go
    (let [results (loop [results []]
                    (if-let [next-res (async/<! results-ch)]
                      (recur (into results next-res))
                      results))]
      (sort comparator results))))

(defn where
  [parsed-query error-ch fuel max-fuel db]
  (let [{:keys [order-by]} parsed-query
        where-results (resolve-where-clause db parsed-query error-ch fuel max-fuel)
        out-ch        (if order-by
                        (order-results where-results error-ch fuel max-fuel order-by)
                        where-results)]
    out-ch))
