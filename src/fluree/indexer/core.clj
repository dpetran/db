(ns fluree.indexer.core
  (:require
   [clojure.core.async :as async]
   [fluree.common.model :as model]
   [fluree.common.protocols :as service-proto]
   [fluree.db.api.query :as jld-query]
   [fluree.db.json-ld.commit :as jld-commit]
   [fluree.db.json-ld.transact :as jld-transact]
   [fluree.db.util.async :refer [<?? go-try]]
   [fluree.db.util.log :as log]
   [fluree.indexer.db :as db]
   [fluree.indexer.model :as idxr-model]
   [fluree.indexer.protocols :as idxr-proto]
   [fluree.store.api :as store]
   [fluree.db.indexer.proto :as idx-proto]
   [fluree.json-ld :as json-ld]))

(defn stop-indexer
  [idxr]
  ;; TODO: call idx-proto/-index when stopping to flush novelty to Store
  (log/info "Stopping Indexer " (service-proto/id idxr) ".")
  (store/stop (:store idxr)))

(defn init-db
  [{:keys [store config db-map] :as idxr} ledger-name opts]
  (let [db (db/create store (merge config opts))
        db-address (db/create-db-address db ledger-name)]
    (if (get @db-map db-address)
      db-address
      (do
        (swap! db-map assoc db-address db)
        db-address))))

(defn stage-db
  [{:keys [store db-map] :as idxr} db-address data]
  (if-let [db-before (get @db-map db-address)]
    (let [db-after   (<?? (jld-transact/stage (db/prepare db-before) data {}))
          ;; This is a hack to sync t into various places since dbs shouldn't care about branches
          ;; used in do-index > refresh > index-update > empty-novelty
          db-after   (assoc-in db-after [:commit :data :t] (- (:t db-after)))

          db-address (db/create-db-address db-after)
          idx        (-> db-after :ledger :indexer)

          {:keys [context did private push?] :as _opts} data

          context*      (-> (if context
                              (json-ld/parse-context (:context (:schema db-after)) context)
                              (:context (:schema db-after)))
                            (json-ld/parse-context {"f" "https://ns.flur.ee/ledger#"})
                            jld-commit/stringify-context)

          ctx-used-atom (atom {})
          compact-fn (json-ld/compact-fn context* ctx-used-atom)

          {:keys [assert retract] :as c}
          (<?? (jld-commit/commit-opts->data db-after {:compact-fn compact-fn :id-key "@id" :type-key "@type"}))

          db-final (if (idx-proto/-index? idx db-after)
                     (<?? (idx-proto/-index idx db-after))
                     db-after)]
      (swap! db-map assoc db-address db-final)
      {:db/address db-address
       :db/v       0
       :db/t       (- (:t db-final))
       :db/flakes  (-> db-final :stats :flakes)
       :db/size    (-> db-final :stats :size)
       :db/context @ctx-used-atom
       :db/assert  assert
       :db/retract retract})
    (throw (ex-info "No such db-address." {:error      :stage/no-such-db
                                           :db-address db-address}))))

(defn discard-db
  [{:keys [store db-map] :as idxr} db-address]
  (swap! db-map dissoc db-address)
  :idxr/discarded)

(defn query-db
  [{:keys [store db-map] :as idxr} db-address query]
  (if-let [db (get @db-map db-address)]
    (<?? (jld-query/query-async db query))
    (throw (ex-info "No such db-address." {:error :query/no-such-db
                                           :db-address db-address}))))

(defrecord Indexer [id]
  service-proto/Service
  (id [_] id)
  (stop [idxr] (stop-indexer idxr))

  idxr-proto/Indexer
  (init [idxr ledger-name opts] (init-db idxr ledger-name opts))
  (stage [idxr db-address data] (stage-db idxr db-address data))
  (query [idxr db-address query] (query-db idxr db-address query))
  (explain [idxr db-address query] (throw (ex-info "TODO" {:todo :explain-not-implemented}))))

(defn create-indexer
  [{:keys [:idxr/id :idxr/store-config :idxr/store] :as config}]
  (let [store (or store (store/start store-config))
        id (or id (random-uuid))]
    (log/info "Starting Indexer " id "." config)
    (map->Indexer {:id id :store store :config config :db-map (atom {})})))

(defn start
  [config]
  (if-let [validation-error (model/explain idxr-model/IndexerConfig config)]
    (throw (ex-info "Invalid indexer config." {:errors (model/report validation-error)
                                               :config config}))
    (create-indexer config)))

(defn stop
  [idxr]
  (service-proto/stop idxr))

(defn init
  [idxr ledger-name opts]
  (idxr-proto/init idxr ledger-name opts))

(defn stage
  [idxr db-address data]
  (idxr-proto/stage idxr db-address data))

(defn discard
  [idxr db-address]
  (idxr-proto/discard idxr db-address))

(defn query
  [idxr db-address query]
  (idxr-proto/query idxr db-address query))

(defn explain
  [idxr db-address query]
  (idxr-proto/explain idxr db-address query))
