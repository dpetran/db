(ns fluree.db.json-ld.reify
  (:require [fluree.json-ld :as json-ld]
            [fluree.db.flake :as flake]
            [fluree.db.constants :as const]
            [fluree.db.json-ld.ledger :as jld-ledger]
            [fluree.db.json-ld.vocab :as vocab]
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.db.util.core :as util :refer [try* catch*]]
            [fluree.db.conn.proto :as conn-proto]
            [fluree.db.util.log :as log]))

;; generates a db/ledger from persisted data
#?(:clj (set! *warn-on-reflection* true))

(def ^:const max-vocab-sid (flake/max-subject-id const/$_collection))

(defn get-iri-sid
  "Gets the IRI for any existing subject ID."
  [iri db iris]
  (if-let [cached (get @iris iri)]
    cached
    ;; TODO following, if a retract was made there could be 2 matching flakes and want to make sure we take the latest .-op = true
    (when-let [sid (some-> (flake/match-post (get-in db [:novelty :post]) const/$iri iri)
                           first
                           :s)]
      (vswap! iris assoc iri sid)
      sid)))


(defn get-vocab-flakes
  [flakes]
  (flake/subrange flakes
                  >= (flake/->Flake (flake/max-subject-id const/$_collection) -1 nil nil nil nil)
                  <= (flake/->Flake 0 -1 nil nil nil nil)))


(defn retract-node
  [db node t iris]
  (let [{:keys [id type]} node
        sid              (or (get-iri-sid id db iris)
                             (throw (ex-info (str "Retractions specifies an IRI that does not exist: " id
                                                  " at db t value: " t ".")
                                             {:status 400 :error :db/invalid-commit})))
        type-retractions (when type
                           (mapv (fn [type-item]
                                   (let [type-id (or (get-iri-sid type-item db iris)
                                                     (throw (ex-info (str "Retractions specifies an @type that does not exist: " type-item)
                                                                     {:status 400 :error :db/invalid-commit})))]
                                     (flake/->Flake sid const/$rdf:type type-id t false nil)))
                                 type))]
    (reduce-kv
      (fn [acc k v-map]
        (if (keyword? k)
          acc
          (let [pid (or (get-iri-sid k db iris)
                        (throw (ex-info (str "Retraction on a property that does not exist: " k)
                                        {:status 400 :error :db/invalid-commit})))]
            (conj acc (flake/->Flake sid pid (:value v-map) t false nil)))))
      (or type-retractions [])
      node)))


(defn retract-flakes
  [db retractions t iris]
  (reduce
    (fn [acc node]
      (into acc
            (retract-node db node t iris)))
    []
    retractions))


(defn assert-node
  [db node t iris refs next-pid next-sid]
  (let [{:keys [id type]} node
        existing-sid    (get-iri-sid id db iris)
        sid             (or existing-sid
                            (jld-ledger/generate-new-sid node iris next-pid next-sid))
        type-assertions (if type
                          (mapcat (fn [type-item]
                                    (let [existing-id (or (get-iri-sid type-item db iris)
                                                          (get jld-ledger/predefined-properties type-item))
                                          type-id     (or existing-id
                                                          (jld-ledger/generate-new-pid type-item iris next-pid nil nil))
                                          type-flakes (when-not existing-id
                                                        [(flake/->Flake type-id const/$iri type-item t true nil)
                                                         (flake/->Flake type-id const/$rdf:type const/$rdfs:Class t true nil)])]
                                      (into [(flake/->Flake sid const/$rdf:type type-id t true nil)]
                                            type-flakes)))
                                  type)
                          [])
        base-flakes     (if existing-sid
                          type-assertions
                          (conj type-assertions (flake/->Flake sid const/$iri id t true nil)))]
    (reduce-kv
      (fn [acc k {:keys [id] :as v-map}]
        (if (keyword? k)
          acc
          (let [existing-pid (get-iri-sid k db iris)
                pid          (or existing-pid
                                 (get jld-ledger/predefined-properties k)
                                 (jld-ledger/generate-new-pid k iris next-pid id refs))]
            (cond-> (if id                                  ;; is a ref to another IRI
                      (let [existing-sid (get-iri-sid id db iris)
                            ref-sid      (or existing-sid
                                             (jld-ledger/generate-new-sid v-map iris next-pid next-sid))]
                        (cond-> (conj acc (flake/->Flake sid pid ref-sid t true nil))
                                (nil? existing-sid) (conj (flake/->Flake ref-sid const/$iri id t true nil))))
                      (conj acc (flake/->Flake sid pid (:value v-map) t true nil)))
                    (nil? existing-pid) (conj (flake/->Flake pid const/$iri k t true nil))))))
      base-flakes
      node)))


(defn assert-flakes
  [db assertions t iris refs]
  (let [last-pid (volatile! (jld-ledger/last-pid db))
        last-sid (volatile! (jld-ledger/last-sid db))
        next-pid (fn [] (vswap! last-pid inc))
        next-sid (fn [] (vswap! last-sid inc))
        flakes   (reduce
                   (fn [acc node]
                     (into acc
                           (assert-node db node t iris refs next-pid next-sid)))
                   []
                   assertions)]
    {:flakes flakes
     :pid    @last-pid
     :sid    @last-sid}))


(defn merge-flakes
  [{:keys [novelty stats ecount] :as db} t refs flakes]
  (let [bytes #?(:clj (future (flake/size-bytes flakes))    ;; calculate in separate thread for CLJ
                 :cljs (flake/size-bytes flakes))
        {:keys [spot psot post opst tspo size]} novelty
        vocab-flakes  (get-vocab-flakes flakes)
        schema        (vocab/update-with db t refs vocab-flakes)
        db*           (assoc db :t t
                                :novelty {:spot (into spot flakes)
                                          :psot (into psot flakes)
                                          :post (into post flakes)
                                          :opst (->> flakes
                                                     (sort-by flake/p)
                                                     (partition-by flake/p)
                                                     (reduce
                                                       (fn [opst* p-flakes]
                                                         (let [pid (flake/p (first p-flakes))]
                                                           (if (or (refs pid) ;; refs is a set of ref pids processed in this commit
                                                                   (get-in schema [:pred pid :ref?]))
                                                             (into opst* p-flakes)
                                                             opst*)))
                                                       opst))
                                          :tspo (into tspo flakes)
                                          :size (+ size #?(:clj @bytes :cljs bytes))}
                                :stats (-> stats
                                           (update :size + #?(:clj @bytes :cljs bytes)) ;; total db ~size
                                           (update :flakes + (count flakes)))
                                :schema schema)]
    db*
    #_(if vocab-change?
        (let [all-refs     (into (get-in db [:schema :refs]) refs)
              vocab-flakes (get-vocab-flakes (get-in db* [:novelty :spot]))]
          (assoc db* :schema (vocab/vocab-map* t all-refs vocab-flakes)))
        db*)))

(defn commit-error
  [message commit-data]
  (throw (ex-info message {:status 400 :error :db/invalid-commit :commit commit-data})))

(defn db-t
  "Returns 't' value from commit data."
  [db-data]
  (let [db-t (get-in db-data [const/iri-t :value])]
    (when-not (pos-int? db-t)
      (commit-error (str "Invalid, or non existent 't' value inside commit: " db-t) db-data))
    db-t))

(defn db-assert
  [db-data]
  (let [commit-assert (get-in db-data [const/iri-assert])]
    ;; TODO - any basic validation required
    commit-assert))

(defn db-retract
  [db-data]
  (let [commit-retract (get-in db-data [const/iri-retract])]
    ;; TODO - any basic validation required
    commit-retract))

(defn parse-commit
  "Given a full commit json, returns two-tuple of [commit-data commit-proof]"
  [commit-data]
  (let [cred-subj (get commit-data "https://www.w3.org/2018/credentials#credentialSubject")
        commit    (or cred-subj commit-data)]
    [commit (when cred-subj commit-data)]))

(defn read-commit
  [conn commit-address]
  (go-try
    (let [file-data (<? (conn-proto/-c-read conn commit-address))]
      (json-ld/expand file-data))))

(defn merge-commit
  [conn {:keys [ecount t] :as db} commit include-db?]
  (go-try
    (let [iris           (volatile! {})
          refs           (volatile! (-> db :schema :refs))
          db-id          (get-in commit [const/iri-db :id])
          db-data        (<? (read-commit conn db-id))
          t-new          (- (db-t db-data))
          _              (when (and (not= t-new (dec t))
                                    (not include-db?)) ;; when including multiple dbs, t values will get reused.
                           (throw (ex-info (str "Commit t value: " (- t-new)
                                                " has a gap from latest commit t value: " (- t) ".")
                                           {:status 500 :error :db/invalid-commit})))
          assert         (db-assert db-data)
          retract        (db-retract db-data)
          retract-flakes (retract-flakes db retract t-new iris)
          {:keys [flakes pid sid]} (assert-flakes db assert t-new iris refs)
          all-flakes     (-> (empty (get-in db [:novelty :spot]))
                             (into retract-flakes)
                             (into flakes))
          ecount*        (assoc ecount const/$_predicate pid
                                       const/$_default sid)]
      (when (empty? all-flakes)
        (commit-error "Commit has neither assertions or retractions!" commit))
      (merge-flakes (assoc db :ecount ecount*) t-new @refs all-flakes))))


(defn trace-commits
  "Returns a list of two-tuples each containing [commit proof] as applicable.
  First commit will be t value of '1' and increment from there."
  [conn latest-commit]
  (go-try
    (loop [next-commit latest-commit
           commits     (list)]
      (let [[commit proof] (parse-commit next-commit)
            db          (get-in commit [const/iri-db :id])
            prev-commit (get-in commit [const/iri-prevCommit :id])
            commits*    (conj commits [commit proof])]
        (when-not db
          (throw (ex-info (str "Commit is not a properly formatted Fluree commit: " next-commit ".")
                          {:status      500
                           :error       :db/invalid-commit
                           :commit-data (if (> (count (str commit)) 500)
                                          (str (subs (str commit) 0 500) "...")
                                          (str commit))})))
        (if prev-commit
          (let [commit-data (<? (read-commit conn prev-commit))]
            (recur commit-data commits*))
          commits*)))))


;; TODO - validate commit signatures
(defn validate-commit
  "Run proof validation, if exists.
  Return actual commit data. In the case of a VerifiableCredential this is
  the `credentialSubject`."
  [db commit proof]
  ;; TODO - returning true for now
  true)


(defn load-db
  [{:keys [ledger] :as db} latest-commit include-db?]
  (go-try
    (let [{:keys [conn]} ledger
          commits (<? (trace-commits conn latest-commit))]
      (loop [[[commit proof] & r] commits
             db* db]
        (when proof
          (validate-commit db* commit proof))
        (if commit
          (let [new-db (<? (merge-commit conn db* commit include-db?))]
            (recur r new-db))
          db*)))))