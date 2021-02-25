(ns fluree.db.query.range
  (:require [fluree.db.dbproto :as dbproto]
            [fluree.db.constants :as const]
            [fluree.db.util.schema :as schema-util]
            [fluree.db.util.core :as util :refer [try* catch*]]
            [fluree.db.util.json :as json]
            [fluree.db.flake :as flake #?@(:cljs [:refer [Flake]])]
            #?(:clj  [clojure.core.async :refer [chan go go-loop <! >!] :as async]
               :cljs [cljs.core.async :refer [go chan <! >!] :as async])
            #?(:clj [fluree.db.permissions-validate :as perm-validate])
            [fluree.db.util.async :refer [<? go-try]])
  #?(:clj (:import (fluree.db.flake Flake)))
  #?(:cljs (:require-macros [fluree.db.util.async])))

(defn- pred-id-strict
  "Will throw if predicate doesn't exist."
  [db p]
  (when p
    (or (dbproto/-p-prop db :id p)
        (throw (ex-info (str "Invalid predicate, does not exist: " p)
                        {:status 400, :error :db/invalid-predicate})))))


(defn- match->flake-parts
  "Takes a match from index-range, and based on the index
  returns flake-ordered components of [s p o t op m].
  Coerces idents and string predicate names."
  [db idx match]
  (let [[p1 p2 p3 p4 op m] match]
    (case idx
      :spot [p1 (dbproto/-p-prop db :id p2) p3 p4 op m]
      :psot [p2 (dbproto/-p-prop db :id p1) p3 p4 op m]
      :post [p3 (dbproto/-p-prop db :id p1) p2 p4 op m]
      :opst [p3 (dbproto/-p-prop db :id p2) p1 p4 op m]
      :tspo [p2 (dbproto/-p-prop db :id p3) p4 p1 op m])))


(def ^{:private true :const true} subject-min-match [util/max-long])
(def ^{:private true :const true} subject-max-match [util/min-long])
(def ^{:private true :const true} pred-min-match [0])
(def ^{:private true :const true} pred-max-match [flake/MAX-PREDICATE-ID])
(def ^{:private true :const true} txn-max-match [util/min-long])
(def ^{:private true :const true} txn-min-match [0])


(defn- min-match
  "Smallest index flake part match by index"
  [idx]
  (case idx
    :spot subject-min-match
    :psot pred-min-match
    :post pred-min-match
    :opst subject-min-match
    :tspo txn-min-match))


(defn- max-match
  "Biggest index flake part match by index"
  [idx]
  (case idx
    :spot subject-max-match
    :psot pred-max-match
    :post pred-max-match
    :opst subject-max-match
    :tspo txn-max-match))


(defn resolve-subid
  [db id]
  (let [out (chan)]
    (if-not id
      (async/close! out)
      (if (util/pred-ident? id)
        (-> db
            (dbproto/-subid id)
            (async/pipe out))
        (async/put! out id)))
    out))

(defn resolve-flake
  [db test parts]
  (go-try
   (let [[s p o t op m] parts
         s' (<? (resolve-subid db s))
         o' (<? (resolve-subid db o))
         m' (or m (if (identical? >= test) util/min-integer util/max-integer))]
     (flake/->Flake s' p o' t op m'))))

(defn index-node-stream
  [root-ch flake-compare start-flake end-flake]
  (let [out (chan)]
    (go-loop []
      (if-let [root-node (<? root-ch)]
        (loop [next-flake start-flake]
          (if-not (and next-flake
                       (not (pos? (flake-compare next-flake end-flake))))
            (async/close! out)
            (let [next-node (<! (dbproto/-lookup-leaf root-node next-flake))]
              (when (>! out next-node)
                (recur (dbproto/-rhs next-node))))))
        (async/close! out)))
    out))

(defn expand-history-range
  [node-stream from-t to-t novelty start-test start-flake end-test end-flake]
  (let [out (chan)]
    (go-loop []
      (if-let [next-node (<! node-stream)]
        (let [subrange-ch   (chan 1 (mapcat (fn [flakes]
                                              (flake/subrange flakes
                                                              start-test start-flake
                                                              end-test end-flake))))
              history-range (-> next-node
                                (dbproto/-resolve-history-range from-t to-t
                                                                novelty)
                                (async/pipe subrange-ch))]
          (loop []
            (when-let [next-flake (<! history-range)]
              (if-not (>! out next-flake)
                (async/close! node-stream)
                (recur))))
          (recur))
        (async/close! out)))
    out))

(defn resolve-nodes-to-t
  [nodes novelty fast-forward-db? t]
  (let [out (chan)]
    (go-loop []
      (if-let [node (<! nodes)]
        (when-let [resolved (try*
                             (<? (dbproto/-resolve-to-t node t novelty fast-forward-db?))
                             (catch* e nil))]
          (if (>! out resolved)
            (recur)
            (async/close! nodes)))
        (async/close! out)))
    out))

(defn filter-authorized
  [flake-range-stream {:keys [permissions] :as db} ^Flake start ^Flake end]
  #?(:cljs
     flake-range-stream ; Note this bypasses all permissions in CLJS for now!

     :clj
     (let [s1 (.-s start)
           p1 (.-p start)
           s2 (.-s end)
           p2 (.-p end)]
       (if (perm-validate/no-filter? permissions s1 s2 p1 p2)
         flake-range-stream
         (let [out (chan)]
           (go-loop []
             (if-let [flake (<! flake-range-stream)]
               (do (when (or (schema-util/is-schema-flake? flake)
                             (<? (perm-validate/allow-flake? db flake)))
                     (>! out flake))
                   (recur))
               (async/close! out)))
           out)))))

(defn take-flakes
  [flake-chan limit]
  (if limit
    (async/take limit flake-chan)
    flake-chan))

(defn expand-range-interval
  "Finds the full index or time range interval including the maximum and minimum
  tests when only one test is provided"
  [idx test match]
  (condp identical? test
    =  [>= match <= match]
    <  [> (min-match idx) < match]
    <= [> (min-match idx) <= match]
    >  [> match <= (max-match idx)]
    >= [>= match < (max-match idx)]))

(defn time-range
  "Range query across an index.

  Uses a DB, but in the future support supplying a connection and db name, as we
  don't need a 't'

  Ranges take the natural numeric sort orders, but all results will return in
  reverse order (newest subjects and predicates first).

  Returns core async channel.

  opts:
  :from-t - start transaction (transaction 't' is negative, so smallest number
            is most recent). Defaults to db's t
  :to-t - stop transaction - can be null, which pulls full history
  :xform - xform applied to each result individually. This is not used
           when :chan is supplied.
  :limit - max number of flakes to return"
  ([db idx] (time-range db idx {}))
  ([db idx opts] (time-range db idx >= (min-match idx) <= (max-match idx) opts))
  ([db idx test match] (time-range db idx test match {}))
  ([db idx test match opts]
   (let [[start-test start-match end-test end-match]
         (expand-range-interval idx test match)]
     (time-range db idx start-test start-match end-test end-match opts)))
  ([db idx start-test start-match end-test end-match]
   (time-range db idx start-test start-match end-test end-match {}))
  ([{t :t :as db} idx start-test start-match end-test end-match opts]
   (let [{:keys [limit from-t to-t]
          :or   {from-t t}}
         opts

         novelty     (get-in db [:novelty idx])
         idx-compare (get-in db [:index-configs idx :comparator])
         out-chan    (chan 1 (map (fn [flakes]
                                    (apply flake/sorted-set-by idx-compare flakes))))]
     (go
       (let [start-parts (match->flake-parts db idx start-match)
             start-flake (<? (resolve-flake db start-test start-parts))
             end-parts   (match->flake-parts db idx end-match)
             end-flake   (<? (resolve-flake db end-test end-parts))]
         (-> db
             (get idx)
             dbproto/-resolve
             (index-node-stream idx-compare start-flake end-flake)
             (expand-history-range from-t to-t novelty
                                   start-test start-flake
                                   end-test end-flake)
             (filter-authorized db start-flake end-flake)
             (take-flakes limit)
             (->> (async/into #{}))
             (async/pipe out-chan))))
     out-chan)))

(defn indexed-flakes-xf
  [{:keys [start-test start-flake end-test end-flake subject-fn predicate-fn
           object-fn]}]
  (let [subrange-fn (fn [flakes]
                      (flake/subrange flakes
                                      start-test start-flake
                                      end-test end-flake))
        xforms      (cond-> [(map :flakes)
                             (mapcat subrange-fn)]
                      subject-fn   (conj (filter (fn [^Flake f]
                                                   (subject-fn (.-s f)))))
                      predicate-fn (conj (filter (fn [^Flake f]
                                                   (predicate-fn (.-p f)))))
                      object-fn    (conj (filter (fn [^Flake f]
                                                   (object-fn (.-o f))))))]
    (apply comp xforms)))

(defn extract-index-flakes
  [node-stream opts]
  (let [extract-chan (chan 1 (indexed-flakes-xf opts))]
    (async/pipe node-stream extract-chan)))

(defn select-flakes-xf
  [{:keys [subject-limit offset]}]
  (let [xforms (cond-> [(partition-by (fn [^Flake f]
                                        (.-s f)))
                        (drop offset)]
                 subject-limit (conj (take subject-limit))
                 :finally      (conj cat))]
    (apply comp xforms)))

(defn select-subject-window
  [flake-stream {:keys [flake-limit] :as opts}]
  (let [select-chan (chan 1 (select-flakes-xf opts))]
    (-> flake-stream
        (async/pipe select-chan)
        (take-flakes flake-limit))))

(defn index-range
  "Range query across an index as of a 't' defined by the db.

  Ranges take the natural numeric sort orders, but all results will
  return in reverse order (newest subjects and predicates first).

  Returns core async channel.

  opts:
  :xform - xform applied to each result individually. This is not used when :chan is supplied.
  :limit - max number of flakes to return"
  ([db idx] (index-range db idx {}))
  ([db idx opts] (index-range db idx >= (min-match idx) <= (max-match idx) opts))
  ([db idx test match] (index-range db idx test match {}))
  ([db idx test match opts]
   (let [[start-test start-match end-test end-match]
         (expand-range-interval idx test match)]
     (index-range db idx start-test start-match end-test end-match opts)))
  ([db idx start-test start-match end-test end-match]
   (index-range db idx start-test start-match end-test end-match {}))
  ([{:keys [permissions t] :as db} idx start-test start-match end-test end-match opts]
   (let [{:keys [flake-limit offset subject-fn predicate-fn object-fn]
          subject-limit :limit, :or {offset 0}}
         opts

         fast-forward-db? (:tt-id db)
         idx-compare      (get-in db [:index-configs idx :comparator])
         novelty          (get-in db [:novelty idx])

         out-chan (chan 1 (map (fn [flakes]
                                 (apply flake/sorted-set-by idx-compare flakes))))]
    (go
      (let [[s1 p1 o1 t1 op1 m1]
            (match->flake-parts db idx start-match)

            [s2 p2 o2 t2 op2 m2]
            (match->flake-parts db idx end-match)

            [[o1 o2] object-fn] (if-let [bool (cond (boolean? o1) o1
                                                    (boolean? o2) o2
                                                    :else nil)]
                                  [[nil nil] (fn [o] (= o bool))]
                                  [[o1 o2] object-fn])

            start-flake (<? (resolve-flake db start-test [s1 p1 o1 t1 op1 m1]))
            end-flake   (<? (resolve-flake db end-test [s2 p2 o2 t2 op2 m2]))]
        (-> db
            (get idx)
            dbproto/-resolve
            (index-node-stream idx-compare start-flake end-flake)
            (resolve-nodes-to-t novelty fast-forward-db? t)
            (extract-index-flakes {:subject-fn subject-fn,
                                   :predicate-fn predicate-fn
                                   :object-fn object-fn
                                   :start-test start-test
                                   :start-flake start-flake
                                   :end-test end-test
                                   :end-flake end-flake})
            (filter-authorized db start-flake end-flake)
            (select-subject-window {:subject-limit subject-limit
                                    :flake-limit flake-limit
                                    :offset offset})
            (->> (async/into #{}))
            (async/pipe out-chan))))
    out-chan)))

(defn non-nil-non-boolean?
  [o]
  (and (not (nil? o))
       (not (boolean? o))))

(defn tag-string?
  [possible-tag]
  (re-find #"^[a-zA-Z0-9-_]*/[a-zA-Z0-9-_]*:[a-zA-Z0-9-]*$" possible-tag))

(def ^:const tag-sid-start (flake/min-subject-id const/$_tag))
(def ^:const tag-sid-end (flake/max-subject-id const/$_tag))

(defn is-tag-flake?
  "Returns true if flake is a root setting flake."
  [^Flake f]
  (<= tag-sid-start (.-o f) tag-sid-end))


(defn coerce-tag-flakes
  [db flakes]
  (async/go-loop [[flake & r] flakes
                  acc []]
    (if flake
      (if (is-tag-flake? flake)
        (let [[s p o t op m] (flake/Flake->parts flake)
              o (<? (dbproto/-tag db o p))]
          (recur r (conj acc (flake/parts->Flake [s p o t op m]))))
        (recur r (conj acc flake))) acc)))

(defn search
  ([db fparts]
   (search db fparts {}))
  ([db fparts opts]
   (go-try (let [[s p o t] fparts
                 idx-predicate? (dbproto/-p-prop db :idx? p)
                 tag-predicate? (if p (= :tag (dbproto/-p-prop db :type p)) false)
                 o-coerce?      (and tag-predicate? (string? o))
                 o              (cond (not o-coerce?)
                                      o

                                      (tag-string? o)
                                      (<? (dbproto/-tag-id db o))
                                      ;; Returns tag-id

                                      ;; if string, but not tag string, we have a string
                                      ;; like "query" with no namespace, we need to ns.
                                      (string? o)
                                      (let [tag-name (str (dbproto/-p-prop db :name p) ":" o)]
                                        (<? (dbproto/-tag-id db tag-name))))

                 res            (cond
                                  s
                                  (<? (index-range db :spot = [s p o t] opts))

                                  (and p (non-nil-non-boolean? o) idx-predicate? (not (fn? o)))
                                  (<? (index-range db :post = [p o s t] opts))

                                  (and p (not idx-predicate?) o)
                                  (let [obj-fn (if-let [obj-fn (:object-fn opts)]
                                                 (fn [x] (and (obj-fn x) (= x o)))
                                                 (fn [x] (= x o)))]
                                    (<? (index-range db :psot = [p s nil t] (assoc opts :object-fn obj-fn))))

                                  p
                                  (<? (index-range db :psot = [p s o t] opts))

                                  o
                                  (<? (index-range db :opst = [o p s t] opts)))
                 res*           (if tag-predicate?
                                  (<? (coerce-tag-flakes db res))
                                  res)]
             res*))))

(defn collection
  "Returns spot index range for only the requested collection."
  ([db name] (collection db name nil))
  ([db name opts]
   (go
     (try*
      (if-let [id (dbproto/-c-prop db :id name)]
        (<? (index-range db :spot
                         >= [(flake/max-subject-id id)]
                         <= [(flake/min-subject-id id)]
                         opts))
        (throw (ex-info (str "Invalid collection name: " (pr-str name))
                        {:status 400
                         :error  :db/invalid-collection})))
      (catch* e e)))))

(defn _block-or_tx-collection
  "Returns spot index range for only the requested collection."
  [db opts]
  (index-range db :spot > [0] <= [util/min-long] opts))

(defn txn-from-flakes
  "Returns vector of transactions from a set of flakes.
   Each transaction is a map with the following keys:
   1. db - the associated ledger
   2. tx - a map containing all transaction data in the original cmd
   3. nonce - the nonce
   4. auth - the authority that submitted the transaction
   5. expire - expiration"
  [flakes]
  (loop [[flake' & r] flakes result* []]
    (if (nil? flake')
      result*
      (let [obj     (.-o flake')
            cmd-map (try*
                     (json/parse obj)
                     (catch* e nil))                       ; log an error if transaction is not parsable?
            {:keys [type db tx nonce auth expire]} cmd-map]
        (recur r
               (if (= type "tx")
                 (conj result* {:db db :tx tx :nonce nonce :auth auth :expire expire})
                 result*))))))

(defn block-with-tx-data
  "Returns block data as a map, with the following keys:
  1. block - block number
  2. t - fluree \"time\" since ledger creation
  3. sigs - List of transactor signatures that signed this block
  4. instant - instant this block was created, per the transactor.
  5. hash - hash of current block
  6. prev-hash - hash of previous block, if relevant
  7. flakes - list of flakes comprising block
  8. txn - list of transactions in block
  "
  [blocks]
  (loop [[block' & r] blocks result* []]
    (if (nil? block')
      result*
      (let [{:keys [block t flakes]} block'
            prev-hash   (some #(when (= (.-p %) const/$_block:prevHash) (.-o %)) flakes)
            hash        (some #(when (= (.-p %) const/$_block:hash) (.-o %)) flakes)
            instant     (some #(when (= (.-p %) const/$_block:instant) (.-o %)) flakes)
            sigs        (some #(when (= (.-p %) const/$_block:sigs) (.-o %)) flakes)
            txn-flakes  (filter #(= (.-p %) const/$_tx:tx) flakes)
            txn-flakes' (txn-from-flakes txn-flakes)]
        (recur r (conj result* {:block     block
                                :t         t
                                :hash      hash
                                :prev-hash prev-hash
                                :instant   instant
                                :sigs      sigs
                                :flakes    flakes
                                :txn       txn-flakes'}))))))
