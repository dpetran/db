(ns fluree.db.query.block
  (:require [fluree.db.storage.core :as storage]
            #?(:clj [fluree.db.permissions-validate :as perm-validate])
            #?(:clj  [clojure.core.async
                      :refer [>! <! >!! <!! go-loop chan buffer close! thread
                              alts! alts!! timeout]
                      :as async]
               :cljs [cljs.core.async :refer [go <!] :as async])
            [fluree.db.util.core :as util]
            [fluree.db.util.async :refer [<?]]))

;; TODO - for nodejs we need to re-enable permissions for javascript but in a
;;        way code only exists for nodejs compilation
(defn block-range
  "Returns an async channel containing each of the blocks from start (inclusive)
  to end (inclusive), if provided. This function differs from
  `fluree.db.storage.core/block-range` by validating permissions for each block
  before inclusion. The `db` argument must therefore be permissioned."
  [{:keys [conn network dbid] :as db} start end opts]
  (let [last-block (or end start)      ;; allow for nil end-block for now
        root?      (-> db :permissions :root?)
        reverse?   (or (nil? end)
                       (< end start))]
    (go-loop [next-block start
              acc        []]
      (let [{:keys [flakes] :as res}
            (<? (storage/block conn network dbid next-block))

            ;; FIXME: this bypasses all permissions in CLJS for now!
            res #?(:cljs res
                   :clj (if root?
                          res
                          {:block  (:block res)
                           :t      (:t res)
                           :flakes (<? (perm-validate/allow-flakes? db flakes))}))

            acc'        (conj acc res)]
        (if (or (= next-block last-block)
                (util/exception? res))
          acc'
          (if reverse?
            (recur (dec next-block) acc')
            (recur (inc next-block) acc')))))))
