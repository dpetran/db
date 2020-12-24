(ns fluree.db.util.schema
  (:require [fluree.db.flake :as flake #?@(:cljs [:refer [Flake]])]
            [fluree.db.constants :as const])
  #?(:clj
     (:import (fluree.db.flake Flake))))


(def ^:const schema-sid-start (flake/min-subject-id const/$_predicate))
(def ^:const schema-sid-end (flake/max-subject-id const/$_collection))

(def ^:const collection-sid-start (flake/min-subject-id const/$_collection))
(def ^:const collection-sid-end (flake/max-subject-id const/$_collection))

(def ^:const predicate-sid-start (flake/min-subject-id const/$_predicate))
(def ^:const predicate-sid-end (flake/max-subject-id const/$_predicate))

(def ^:const setting-sid-start (flake/min-subject-id const/$_setting))
(def ^:const setting-sid-end (flake/max-subject-id const/$_setting))

(def ^:const auth-sid-start (flake/min-subject-id const/$_auth))
(def ^:const auth-sid-end (flake/max-subject-id const/$_auth))

(def ^:const role-sid-start (flake/min-subject-id const/$_role))
(def ^:const role-sid-end (flake/max-subject-id const/$_role))

(def ^:const rule-sid-start (flake/min-subject-id const/$_rule))
(def ^:const rule-sid-end (flake/max-subject-id const/$_rule))

(def ^:const fn-sid-start (flake/min-subject-id const/$_fn))
(def ^:const fn-sid-end (flake/max-subject-id const/$_fn))

(def ^:const tag-sid-start (flake/min-subject-id const/$_tag))
(def ^:const tag-sid-end (flake/max-subject-id const/$_tag))


(defn is-schema-flake?
  "Returns true if flake is a schema flake."
  [^Flake f]
  (<= schema-sid-start (flake/s f) schema-sid-end))

(defn is-setting-flake?
  "Returns true if flake is a root setting flake."
  [^Flake f]
  (<= setting-sid-start (flake/s f) setting-sid-end))

(defn is-language-flake?
  "Returns true if flake is a language flake."
  [^Flake f]
  (= (flake/p f) const/$_setting:language))

(defn is-genesis-flake?
  "Returns true if flake is a root setting flake."
  [^Flake f]
  (cond
    (and (<= tag-sid-start (flake/s f) tag-sid-end)) true
    (is-setting-flake? f) true
    (<= auth-sid-start (flake/s f) auth-sid-end) true
    (<= role-sid-start (flake/s f) role-sid-end) true
    (<= rule-sid-start (flake/s f) rule-sid-end) true
    (<= fn-sid-start (flake/s f) fn-sid-end) true
    (and (<= collection-sid-start (flake/s f) collection-sid-end)
         (<= (flake/sid->i (flake/s f)) const/$numSystemCollections)) true
    (and (<= predicate-sid-start (flake/s f) predicate-sid-end)
         (<= (flake/sid->i (flake/s f)) const/$maxSystemPredicates)) true

    :else false))

(defn add-to-post-preds?
  [flakes pred-ecount]
  (keep (fn [flk]
          (when (and (or (= (flake/p flk) const/$_predicate:index)
                         (= (flake/p flk) const/$_predicate:unique))
                     (= (flake/o flk) true)
                     (>= pred-ecount (flake/s flk)))
            (flake/s flk)))
        flakes))

(defn remove-from-post-preds
  [flakes]
  (keep (fn [flk]
          (when (and (true? (flake/op flk))
                     (or (= (flake/p flk) const/$_predicate:index)
                         (= (flake/p flk) const/$_predicate:unique))
                     (= (flake/o flk) false))
            (flake/s flk)))
        flakes))

(defn schema-change?
  "Returns true if any of the provided flakes are a schema flake."
  [flakes]
  (some is-schema-flake? flakes))

(defn setting-change?
  [flakes]
  (some is-setting-flake? flakes))


(defn get-language-change
  "Returns the language being added, if any. Else returns nil."
  [flakes]
  (some #(when (and (is-language-flake? %)
                    (is-setting-flake? %)
                    (true? (flake/op %))) (flake/o %)) flakes))

(defn is-pred-flake?
  "Returns true if flake is a schema flake."
  [^Flake f]
  (<= flake/MIN-PREDICATE-ID (flake/s f) flake/MAX-PREDICATE-ID))


(defn pred-change?
  "Returns true if there are any predicate changes present in set of flakes."
  [flakes]
  (some is-pred-flake? flakes))
