(ns user
  (:require [fluree.db.json-ld.api :as fluree]
            [clojure.tools.namespace.repl :as tn :refer [refresh refresh-all]]
            [clojure.core.async :as async]
            [fluree.db.did :as did]
            [fluree.db.json-ld.api :as fluree]
            [fluree.db.util.async :refer [<? go-try merge-into?]]
            [fluree.db.flake :as flake]
            [fluree.db.dbfunctions.fns :as dbfunctions]
            [fluree.db.constants :as constants]
            [fluree.db.util.json :as json]
            [fluree.db.serde.json :as serdejson]
            [fluree.db.storage.core :as storage]
            [fluree.db.query.fql :as fql]
            [fluree.db.query.range :as query-range]
            [fluree.db.dbproto :as dbproto]
            [fluree.db.constants :as const]
            [fluree.db.query.schema :as schema]
            [clojure.string :as str]
            [criterium.core :refer [bench]]
            ;; cljs
            [figwheel-sidecar.repl-api :as ra]))


;; async/query todo
;; fluree.db.query.fql/query
;; dbproto/-query
;; api/query
;; fluree.db.dbfunctions.internal/query


;; make sure all of following response are async
;; http-api/db-handler* (transator)


(set! *warn-on-reflection* true)



(comment



  (do
    (def default-private-key
      "8ce4eca704d653dec594703c81a84c403c39f262e54ed014ed857438933a2e1c")


    (def did (did/private->did-map default-private-key ))

    (def default-context
      {:id     "@id"
       :type   "@type"
       :xsd    "http://www.w3.org/2001/XMLSchema#"
       :rdf    "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
       :rdfs   "http://www.w3.org/2000/01/rdf-schema#"
       :sh     "http://www.w3.org/ns/shacl#"
       :schema "http://schema.org/"
       :skos   "http://www.w3.org/2008/05/skos#"
       :wiki   "https://www.wikidata.org/wiki/"
       :f      "https://ns.flur.ee/ledger#"}))

  (def file-conn @(fluree/connect {:method :file
                                   :storage-path "dev/data"
                                   :defaults
                                   {:context (merge default-context {:ex "http://example.org/ns/"})
                                    :did did}}))



  (def ledger @(fluree/create file-conn "user/test"))

  (def db1 @(fluree/stage
            (fluree/db ledger)
            [{:context      {:ex "http://example.org/ns/"}
              :id           :ex/brian,
              :type         :ex/User,
              :schema/name  "Brian"
              :schema/email "brian@example.org"
              :schema/age   50
              :ex/favNums   7
              }
             {:context      {:ex "http://example.org/ns/"}
              :id           :ex/alice,
              :type         :ex/User,
              :schema/name  "Alice"
              :schema/email "alice@example.org"
              :schema/age   50
              :ex/favNums   [42, 76, 9]
              }
             {:context      {:ex "http://example.org/ns/"}
              :id           :ex/cam,
              :type         :ex/User,
              :schema/name  "Cam"
              :schema/email "cam@example.org"
              :schema/age   34
              :ex/favNums   [5, 10]
              :ex/friend    [:ex/brian :ex/alice]}]))

  (def db2 @(fluree/commit! ledger db1 {:message "hi"}))

  @(fluree/load file-conn (-> ledger :alias))

  )

;; TODO - general
;; if an subject is a component, do we allow it to be explicitly assigned as a ref to another component?
;; If so, do we not retract it? If we do retract it, we need to also retract all other refs
;; For permissions, if the predicate or collection IDs don't exist, we can skip the rule

;; TODO - query
;; Tag queries are always allowed, but we still do a full permissions check. Currently have an exception in
;; graphdb no-filter? but should probably have a separate method that shortcuts range-query
;; Make graphql use standard query path
;; graphQL schema will create a 'collection' based on predicate namespace alone, even if the collection doesn't exist
;; Some sort of defined-size tag lookup cache could make sense. Would need to clear it if a transaction altered any tag


                                        ;cljs-stuff
(defn start [] (ra/start-figwheel!))
(defn start-cljs [] (ra/cljs-repl "dev"))
                                        ;(defn stop [] (ra/stop-figwheel!))  ;; errs
