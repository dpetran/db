(ns fluree.store.file
  (:refer-clojure :exclude [exists? list])
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [fluree.common.identity :as ident]
   [fluree.common.protocols :as service-proto]
   [fluree.common.util :as util]
   [fluree.db.index]
   [fluree.db.util.async :refer [<? go-try]]
   [fluree.db.util.json :as json]
   [fluree.db.util.log :as log]
   [fluree.store.protocols :as store-proto]
   [fluree.store.resolver :as resolver]
   [fluree.db.serde.avro :as avro-serde]
   [fluree.crypto :as crypto]
   [fluree.db.conn.cache :as conn-cache])
  (:import
   (java.io File OutputStream ByteArrayOutputStream FileNotFoundException)))

(set! *warn-on-reflection* true)

(defn stop-file-store
  "Notify of Store stopping. No state to dispose of."
  [store]
  (log/info (str "Stopping FileStore " (service-proto/id store) " " (:storage-path store ) "."))
  :stopped)

(defn address-file
  [type path]
  (ident/create-address type :file path))

(defn write-file
  "Write string to disk at the given file path."
  [base-path path data serialize-to {:keys [serializer content-address?] :as _opts}]
  ;; TODO: use a proper serde here, from config. Avro needs schemas for everything it writes...
  (let [serialized (cond (bytes? data)          data
                         (= serialize-to :json) (json/stringify data)
                         (= serialize-to :edn)  (pr-str data))
        bytes      (if (string? serialized)
                     (util/string->bytes serialized)
                     serialized)
        hash       (crypto/sha2-256 bytes)
        path       (str path (when content-address? hash))
        file-path  (str base-path path)]
    (try
      (with-open [out (io/output-stream (io/file file-path))]
        (.write out ^bytes bytes))
      {:path    path
       :id      hash
       :address path
       :hash    hash}
      (catch FileNotFoundException _
        (try
          (io/make-parents (io/file file-path))
          (with-open [out (io/output-stream (io/file file-path))]
            (.write out ^bytes bytes))
          {:path    path
           :id      hash
           :address path
           :hash    hash}
          (catch Exception e
            (log/error (str "Unable to create storage directory: " path
                            " with error: " (.getMessage e) "."))
            (throw e))))
      (catch Exception e (throw e)))))

(defn read-file
  "Read string from disk at given file path."
  [base-path path serialize-to {:keys [deserializer] :as _opts}]
  ;; TODO: proper serde support here
  (try
    (with-open [xin (io/input-stream (str base-path path))
                xout (ByteArrayOutputStream.)]
      (io/copy xin xout)
      (let [serialized (String. (.toByteArray xout))]
        (case serialize-to
          :json (json/parse serialized false)
          :edn (edn/read-string serialized))))
    (catch FileNotFoundException _
      nil)))

(defn delete-file
  "Delete file from disk at given file path."
  [base-path path]
  (try
    (io/delete-file (io/file (str base-path path)))
    :deleted
    (catch Exception e
      (log/error (str "Failed to delete file: " path " with error: " (.getMessage e) ".")))))

(defn list-files
  [base-path prefix-path serialize-to]
  (try
    (->> (.listFiles (io/file (str base-path prefix-path)))
         (map #(str prefix-path (.getName ^File %))))
    (catch Exception e
      (log/error (str "Failed to list files at path: " prefix-path " with error: " (.getMessage e) ".")))))

(defrecord FileStore [id serialize-to storage-path lru-cache-atom]
  service-proto/Service
  (id [_] id)
  (stop [store] (stop-file-store store))

  store-proto/Store
  (address [_ type path] (address-file type path))
  (read [_ path] (go-try (read-file storage-path path serialize-to {})))
  (read [_ path opts] (go-try (read-file storage-path path serialize-to opts)))
  (list [_ prefix] (go-try (list-files storage-path prefix serialize-to)))
  (write [_ path data] (go-try (write-file storage-path path data serialize-to {})))
  (write [_ path data opts] (go-try (write-file storage-path path data serialize-to opts)))
  (delete [_ path] (go-try (delete-file storage-path path)))

  fluree.db.index/Resolver
  (resolve [store node] (resolver/resolve-node store lru-cache-atom node)))

(defn create-file-store
  [{:keys [:store/id :store/serde :store/memory store/lru-cache-atom
           :file-store/storage-path :file-store/serialize-to ] :as config}]
  (let [id (or id (random-uuid))

        cache-size     (conn-cache/memory->cache-size memory)
        lru-cache-atom (or lru-cache-atom (atom (conn-cache/create-lru-cache cache-size)))]
    (log/info "Started FileStore." id)
    (map->FileStore {:id id
                     :serialize-to serialize-to
                     :lru-cache-atom lru-cache-atom
                     :serializer (or serde (avro-serde/->Serializer))
                     :storage-path (util/ensure-trailing-slash storage-path)})))
