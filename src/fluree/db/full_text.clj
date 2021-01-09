(ns fluree.db.full-text
  (:require [clojure.string :as str]
            [clucie.store :as store]))

(defn storage-path
  [base-path network dbid]
  (str/join "/" [base-path network dbid "lucene"]))

(defn storage
  [base-path network dbid]
  (let [path (storage-path base-path network dbid)]
    (store/disk-store path)))
