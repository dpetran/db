(ns fluree.db.json-ld.reify
  (:require [fluree.json-ld :as json-ld]
            [fluree.db.flake :as flake]
            [fluree.db.constants :as const]
            [fluree.db.json-ld.ledger :as jld-ledger]
            [fluree.db.json-ld.vocab :as vocab]
            [fluree.db.util.log :as log]))

;; generates a db/ledger from persisted data
#?(:clj (set! *warn-on-reflection* true))

(def ^:const max-vocab-sid (flake/max-subject-id const/$_collection))

(defn get-iri-sid
  "Gets the IRI for any existing subject ID."
  [iri db iris]
  (if-let [cached (get @iris iri)]
    cached
    ;; TODO following, if a retract was made there could be 2 matching flakes and want to make sure we take the latest add:true
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
                             (throw (ex-info (str "Retractions specifies an IRI that does not exist: " id)
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
        next-sid (fn [] (vswap! last-sid inc))]
    (reduce
      (fn [acc node]
        (into acc
              (assert-node db node t iris refs next-pid next-sid)))
      []
      assertions)))


(defn merge-flakes
  [{:keys [novelty stats] :as db} t refs flakes]
  (let [bytes #?(:clj (future (flake/size-bytes flakes))    ;; calculate in separate thread for CLJ
                 :cljs (flake/size-bytes flakes))
        {:keys [spot psot post opst tspo size]} novelty
        flakes*       (sort-by flake/p flakes)
        vocab-change? (<= (flake/s (first flakes*)) max-vocab-sid) ;; flakes are sorted, so lowest sid of all flakes will be first
        db*           (assoc db :t t
                                :novelty {:spot (into spot flakes)
                                          :psot (into psot flakes)
                                          :post (into post flakes)
                                          :opst (->> flakes*
                                                     (partition-by flake/p)
                                                     (reduce
                                                       (fn [opst* p-flakes]
                                                         (let [pid (flake/p (first p-flakes))]
                                                           (if (or (refs pid) ;; refs is a set of ref pids processed in this commit
                                                                   (get-in db [:schema :pred pid :ref?]))
                                                             (into opst* p-flakes)
                                                             opst*)))
                                                       opst))
                                          :tspo (into tspo flakes)
                                          :size (+ size #?(:clj @bytes :cljs bytes))}
                                :stats (-> stats
                                           (update :size + #?(:clj @bytes :cljs bytes)) ;; total db ~size
                                           (update :flakes + (count flakes))))]
    (if vocab-change?
      (let [all-refs     (into (get-in db [:schema :refs]) refs)
            vocab-flakes (get-vocab-flakes (get-in db* [:novelty :spot]))]
        (assoc db* :schema (vocab/vocab-map* t all-refs vocab-flakes)))
      db*)))


(defn merge-commit
  [db commit]
  (let [iris           (volatile! {})
        refs           (volatile! (-> db :schema :refs))
        t              (- (get-in commit [const/iri-t :value]))
        assert         (get commit const/iri-assert)
        retract        (get commit const/iri-retract)
        retract-flakes (retract-flakes db retract t iris)
        assert-flakes  (assert-flakes db assert t iris refs)]
    (merge-flakes db t @refs (into assert-flakes retract-flakes))))


(defn load-commit
  "Returns two-tuple of [commit-data commit-wrapper/proof].
  In the case that a Verifiable Credential or JOSE were not used for
  a proof, the second tuple will be nil."
  [read-fn file-id]
  (let [file-data  (read-fn file-id)
        file-data* (json-ld/expand file-data)
        cred-subj  (get file-data* "https://www.w3.org/2018/credentials#credentialSubject")
        commit     (or cred-subj file-data*)]
    [commit (when cred-subj file-data*)]))


(defn trace-commits
  "Returns a list of two-tuples each containing [commit proof] as applicable.
  First commit will be t value of '1' and increment from there."
  [read-fn starting-commit]
  (loop [next-commit starting-commit
         last-t      nil
         commits     (list)]
    (let [[commit proof] (load-commit read-fn next-commit)
          t            (get-in commit [const/iri-t :value])
          next-commit* (get-in commit [const/iri-prev :id])
          commits*     (conj commits [commit proof])]
      (when-not t
        (throw (ex-info (str "Commit is not a properly formatted Fluree commit: " next-commit ".")
                        {:status      500
                         :error       :db/invalid-commit
                         :commit-data (if (> (count (str commit)) 500)
                                        (str (subs (str commit) 0 500) "...")
                                        (str commit))})))
      (when (and last-t (not= t (dec last-t)))
        (throw (ex-info (str "Commit t values are not in sync. Expecting next t value of: " (dec last-t)
                             "however instead got a commit with t value of: " t " @ commit: " next-commit)
                        {:status      500
                         :error       :db/invalid-commit
                         :commit-data (if (> (count (str commit)) 500)
                                        (str (subs (str commit) 0 500) "...")
                                        (str commit))})))
      (if (= 1 t)
        commits*
        (recur next-commit* t commits*)))))


(defn retrieve-genesis
  [{:keys [config] :as db} db-key]
  (let [read-fn (:read config)
        doc     (-> db-key
                    read-fn
                    json-ld/expand)
        t       (get-in doc [const/iri-t :value])]
    (if (= 1 t)
      doc

      (do
        (log/info "DB has no index service, retrieving blockchain of:" t "commits.")
        ))
    doc))


;; TODO - validate commit signatures
(defn validate-commit
  "Run proof validation, if exists.
  Return actual commit data. In the case of a VerifiableCredential this is
  the `credentialSubject`."
  [db commit]
  commit)


(defn load-db
  [{:keys [config] :as db} db-key]
  (let [read-fn (:read config)
        commits (trace-commits read-fn db-key)]
    (reduce
      (fn [db* [commit proof]]
        (when proof
          (validate-commit db* proof))
        (merge-commit db* commit))
      db commits)))