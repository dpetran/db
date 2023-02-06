(ns fluree.db.api.query
  "Primary API ns for any user-invoked actions. Wrapped by language & use specific APIS
  that are directly exposed"
  (:require [clojure.string :as str]
            [clojure.core.async :as async]
            [fluree.db.time-travel :as time-travel]
            [fluree.db.query.fql :as fql]
            [fluree.db.query.fql.parse :as fql-parse]
            [fluree.db.query.history :as history]
            [fluree.db.query.range :as query-range]
            [fluree.db.dbproto :as dbproto]
            [fluree.db.flake :as flake]
            [fluree.db.util.core :as util #?(:clj :refer :cljs :refer-macros) [try* catch*]]
            [fluree.db.util.async :as async-util :refer [<? go-try]]
            [fluree.db.json-ld.credential :as cred]
            [fluree.db.query.json-ld.response :as json-ld-resp]
            [fluree.json-ld :as json-ld]
            [fluree.db.db.json-ld :as jld-db]
            [malli.core :as m]
            [fluree.db.util.log :as log]
            [fluree.db.constants :as const]
            [fluree.db.datatype :as datatype]))

#?(:clj (set! *warn-on-reflection* true))

;; main query interface for APIs, etc.


(declare query)


(defn db-ident?
  [source]
  (= (-> source (str/split #"/") count) 2))


(defn- isolate-ledger-id
  [ledger-id]
  (re-find #"[a-z0-9]+/[a-z0-9]+" ledger-id))



(defn commit-details
  [db query-map]
  (go-try
   (if-not (history/commit-details-query? query-map)
     (throw (ex-info (str "Commit query not properly formatted. Provided "
                          (pr-str query-map))
                     {:status 400
                      :error  :db/invalid-query}))
     (let [{{from :from  to :to} :commit-details} (history/commit-details-query-parser query-map)
           [from-t to-t] [(if from (- from) (:t db)) (if to (- to) (:t db))]
           flakes (<? (query-range/time-range db :tspo = [] {:from-t from-t :to-t to-t}))
           results (<? (history/commit-flakes->json-ld db query-map flakes))]
       results))))


(defn history
  [db query-map]
  (go-try
    (if-not (history/history-query? query-map)
      (throw (ex-info (str "History query not properly formatted. Provided "
                           (pr-str query-map))
                      {:status 400
                       :error  :db/invalid-query}))

      (let [{:keys [history t context]} (history/history-query-parser query-map)

            ;; parses to [:subject <:id>] or [:flake {:s <> :p <> :o <>}]}
            [query-type parsed-query] history

            {:keys [s p o]} (if (= :subject query-type)
                              {:s parsed-query}
                              parsed-query)

            query [(when s (<? (dbproto/-subid db (jld-db/expand-iri db s context) true)))
                   (when p (jld-db/expand-iri db p context))
                   (when o (jld-db/expand-iri db o context))]

            [pattern idx] (history/get-history-pattern query)

            ;; from and to are positive ints, need to convert to negative or fill in default values
            {:keys [from to]} t
            [from-t to-t]     [(cond (string? from) (<? (time-travel/datetime->t db from))
                                     (number? from) (- from)
                                     :else          -1)
                               (cond (string? to) (<? (time-travel/datetime->t db to))
                                     (number? to) (- to)
                                     :else        (:t db))]
            flakes            (<? (query-range/time-range db idx = pattern {:from-t from-t :to-t to-t}))
            results           (<? (history/history-flakes->json-ld db query-map flakes))]
        results))))

(defn query
  "Execute a query against a database source, or optionally
  additional sources if the query spans multiple data sets.
  Returns core async channel containing result."
  [sources query]
  (go-try
   (let [{query :subject, issuer :issuer}
         (or (<? (cred/verify query))
             {:subject query})

         {:keys [opts t]} query
         db               (if (async-util/channel? sources) ;; only support 1 source currently
                            (<? sources)
                            sources)
         db*              (-> (if t
                                (<? (time-travel/as-of db t))
                                db)
                              (assoc-in [:policy :cache] (atom {})))
          meta?         (:meta opts)
          opts*         (assoc opts :issuer issuer)
          start         #?(:clj (System/nanoTime)
                           :cljs (util/current-time-millis))
         result        (<? (fql/query db* (assoc query :opts opts*)))]
      (if meta?
        {:status 200
         :result result
         :time   (util/response-time-formatted start)}
        result))))

(defn multi-query
  "Performs multiple queries in a map, with the key being the alias for the query
  and the value being the query itself. Each query result will be in a response
  map with its respective alias as the key.

  If any errors occur, an :errors key will be present with a map of each alias
  to its error information. Check for the presence of this key if detection of
  an error is important.

  An optional :opts key contains options, which for now is limited to:
   - meta: true or false - If false, will just report out the result as a map.
           If true will roll up all status. Response map will contain keys:
           - status - aggregate status (200 all good, 207 some good, or 400+ for differing errors
           - result - query result
           - errors - map of query alias to their respective error"
  [source flureeQL]
  (go-try
   (let [global-meta?       (get-in flureeQL [:opts :meta]) ;; if true, need to collect meta for each query to total up
         ;; update individual queries for :meta if not otherwise specified
         queries            (reduce-kv
                             (fn [acc alias query]
                               (let [query-meta?  (get-in query [:opts :meta])
                                     meta?        (or global-meta? query-meta?)
                                     remove-meta? (and meta? (not query-meta?)) ;; query didn't ask for meta, but multiquery did so must strip it

                                     opts*        (assoc (:opts query) :meta meta?
                                                         :-remove-meta? remove-meta?)
                                     query*       (assoc query :opts opts*)]
                                 (assoc acc alias query*)))
                             {} (dissoc flureeQL :opts))
         start-time #?(:clj (System/nanoTime) :cljs (util/current-time-millis))
         ;; kick off all queries in parallel, each alias now mapped to core async channel
         pending-resp       (map (fn [[alias q]] [alias (query source q)]) queries)]
     (loop [[[alias port] & r] pending-resp
            status-global nil                            ;; overall status.
            response      {}]
       (if (nil? port)                                   ;; done?
         (if global-meta?
           {:result response
            :status status-global
            :time   (util/response-time-formatted start-time)}
           response)
         (let [{:keys [meta -remove-meta?]} (get-in queries [alias :opts])
               res            (async/<! port)
               error?         (:error res)               ;; if error key is present in response, it is an error
               status-global* (when meta
                                (let [status (:status res)]
                                  (cond
                                    (nil? status-global)
                                    status

                                    (= status-global status)
                                    status

                                    ;; any 200 response with any other is a 207
                                    (or (= 200 status) (= 200 status-global) (= 207 status-global))
                                    207

                                    ;; else take the max status
                                    :else
                                    (max status status-global))))
               response*      (if error?
                                (assoc-in response [:errors alias] res)
                                (assoc response alias (if -remove-meta?
                                                        (:result res)
                                                        res)))]
           (recur r status-global* response*)))))))
