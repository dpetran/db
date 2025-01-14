(ns fluree.db.query.misc-queries-test
  (:require
    [clojure.test :refer :all]
    [fluree.db.test-utils :as test-utils]
    [fluree.db.json-ld.api :as fluree]))

(deftest ^:integration select-sid
  (testing "Select index's subject id in query using special keyword"
    (let [conn   (test-utils/create-conn)
          ledger @(fluree/create conn "query/subid" {:context {:ex "http://example.org/ns/"}})
          db     @(fluree/stage
                    (fluree/db ledger)
                    {:graph [{:id          :ex/alice,
                              :type        :ex/User,
                              :schema/name "Alice"}
                             {:id           :ex/bob,
                              :type         :ex/User,
                              :schema/name  "Bob"
                              :ex/favArtist {:id          :ex/picasso
                                             :schema/name "Picasso"}}]})]
      (is (= [{:_id          211106232532993,
               :id           :ex/bob,
               :rdf/type     [:ex/User],
               :schema/name  "Bob",
               :ex/favArtist {:_id         211106232532994
                              :schema/name "Picasso"}}
              {:_id         211106232532992,
               :id          :ex/alice,
               :rdf/type    [:ex/User],
               :schema/name "Alice"}]
             @(fluree/query db {:select {'?s [:_id :* {:ex/favArtist [:_id :schema/name]}]}
                                :where  [['?s :type :ex/User]]}))))))

(deftest ^:integration s+p+o-full-db-queries
  (testing "Query that pulls entire database."
    (with-redefs [fluree.db.util.core/current-time-iso (fn [] "1970-01-01T00:12:00.00000Z")]
      (let [conn   (test-utils/create-conn)
            ledger @(fluree/create conn "query/everything" {:context {:ex "http://example.org/ns/"}})
            db     @(fluree/stage
                      (fluree/db ledger)
                      {:graph [{:id           :ex/alice,
                                :type         :ex/User,
                                :schema/name  "Alice"
                                :schema/email "alice@flur.ee"
                                :schema/age   42}
                               {:id          :ex/bob,
                                :type        :ex/User,
                                :schema/name "Bob"
                                :schema/age  22}
                               {:id           :ex/jane,
                                :type         :ex/User,
                                :schema/name  "Jane"
                                :schema/email "jane@flur.ee"
                                :schema/age   30}]})]
        (is (= [[:ex/jane :id "http://example.org/ns/jane"]
                [:ex/jane :rdf/type :ex/User]
                [:ex/jane :schema/name "Jane"]
                [:ex/jane :schema/email "jane@flur.ee"]
                [:ex/jane :schema/age 30]
                [:ex/bob :id "http://example.org/ns/bob"]
                [:ex/bob :rdf/type :ex/User]
                [:ex/bob :schema/name "Bob"]
                [:ex/bob :schema/age 22]
                [:ex/alice :id "http://example.org/ns/alice"]
                [:ex/alice :rdf/type :ex/User]
                [:ex/alice :schema/name "Alice"]
                [:ex/alice :schema/email "alice@flur.ee"]
                [:ex/alice :schema/age 42]
                [:schema/age :id "http://schema.org/age"]
                [:schema/email :id "http://schema.org/email"]
                [:schema/name :id "http://schema.org/name"]
                [:ex/User :id "http://example.org/ns/User"]
                [:ex/User :rdf/type :rdfs/Class]
                [:rdfs/Class :id "http://www.w3.org/2000/01/rdf-schema#Class"]
                [:rdf/type :id "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"]
                [:id :id "@id"]]
               @(fluree/query db {:select ['?s '?p '?o]
                                  :where  [['?s '?p '?o]]}))
            "Entire database should be pulled.")
      (let [db* @(fluree/commit! ledger db)]
        (is (= [[:ex/jane :id "http://example.org/ns/jane"]
                [:ex/jane :rdf/type :ex/User]
                [:ex/jane :schema/name "Jane"]
                [:ex/jane :schema/email "jane@flur.ee"]
                [:ex/jane :schema/age 30]
                [:ex/bob :id "http://example.org/ns/bob"]
                [:ex/bob :rdf/type :ex/User]
                [:ex/bob :schema/name "Bob"]
                [:ex/bob :schema/age 22]
                [:ex/alice :id "http://example.org/ns/alice"]
                [:ex/alice :rdf/type :ex/User]
                [:ex/alice :schema/name "Alice"]
                [:ex/alice :schema/email "alice@flur.ee"]
                [:ex/alice :schema/age 42]
                ["did:fluree:TfCzWTrXqF16hvKGjcYiLxRoYJ1B8a6UMH6"
                 :id
                 "did:fluree:TfCzWTrXqF16hvKGjcYiLxRoYJ1B8a6UMH6"]
                ["fluree:db:sha256:bbvrvnmzcotbulq3zo7cdakl3vacysgap6il37m4widvdp7vr353a"
                 :id
                 "fluree:db:sha256:bbvrvnmzcotbulq3zo7cdakl3vacysgap6il37m4widvdp7vr353a"]
                ["fluree:db:sha256:bbvrvnmzcotbulq3zo7cdakl3vacysgap6il37m4widvdp7vr353a"
                 :f/address
                 "fluree:memory://6fc2d43d2625d61ac668360707681aed4607da79a25dc0fef36acbebf24cb28a"]
                ["fluree:db:sha256:bbvrvnmzcotbulq3zo7cdakl3vacysgap6il37m4widvdp7vr353a"
                 :f/flakes
                 25]
                ["fluree:db:sha256:bbvrvnmzcotbulq3zo7cdakl3vacysgap6il37m4widvdp7vr353a"
                 :f/size
                 1888]
                ["fluree:db:sha256:bbvrvnmzcotbulq3zo7cdakl3vacysgap6il37m4widvdp7vr353a"
                 :f/t
                 1]
                [:schema/age :id "http://schema.org/age"]
                [:schema/email :id "http://schema.org/email"]
                [:schema/name :id "http://schema.org/name"]
                [:ex/User :id "http://example.org/ns/User"]
                [:ex/User :rdf/type :rdfs/Class]
                [:rdfs/Class :id "http://www.w3.org/2000/01/rdf-schema#Class"]
                [:rdf/type :id "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"]
                [:f/t :id "https://ns.flur.ee/ledger#t"]
                [:f/size :id "https://ns.flur.ee/ledger#size"]
                [:f/flakes :id "https://ns.flur.ee/ledger#flakes"]
                [:f/context :id "https://ns.flur.ee/ledger#context"]
                [:f/branch :id "https://ns.flur.ee/ledger#branch"]
                [:f/alias :id "https://ns.flur.ee/ledger#alias"]
                [:f/data :id "https://ns.flur.ee/ledger#data"]
                [:f/address :id "https://ns.flur.ee/ledger#address"]
                [:f/v :id "https://ns.flur.ee/ledger#v"]
                ["https://www.w3.org/2018/credentials#issuer"
                 :id
                 "https://www.w3.org/2018/credentials#issuer"]
                [:f/time :id "https://ns.flur.ee/ledger#time"]
                [:f/message :id "https://ns.flur.ee/ledger#message"]
                [:f/previous :id "https://ns.flur.ee/ledger#previous"]
                [:id :id "@id"]
                ["fluree:commit:sha256:bs3bcu3rhzlidsdvg3jgvbplhzxxlevvhipxsnqvfk7em4juu2os"
                 :id
                 "fluree:commit:sha256:bs3bcu3rhzlidsdvg3jgvbplhzxxlevvhipxsnqvfk7em4juu2os"]
                ["fluree:commit:sha256:bs3bcu3rhzlidsdvg3jgvbplhzxxlevvhipxsnqvfk7em4juu2os"
                 :f/time
                 720000]
                ["fluree:commit:sha256:bs3bcu3rhzlidsdvg3jgvbplhzxxlevvhipxsnqvfk7em4juu2os"
                 "https://www.w3.org/2018/credentials#issuer"
                 "did:fluree:TfCzWTrXqF16hvKGjcYiLxRoYJ1B8a6UMH6"]
                ["fluree:commit:sha256:bs3bcu3rhzlidsdvg3jgvbplhzxxlevvhipxsnqvfk7em4juu2os"
                 :f/v
                 0]
                ["fluree:commit:sha256:bs3bcu3rhzlidsdvg3jgvbplhzxxlevvhipxsnqvfk7em4juu2os"
                 :f/address
                 "fluree:memory://aac96d4d42bfecff44a7479259a1c8f3ccecca4df3cf42d177b84f7619b0baae"]
                ["fluree:commit:sha256:bs3bcu3rhzlidsdvg3jgvbplhzxxlevvhipxsnqvfk7em4juu2os"
                 :f/data
                 "fluree:db:sha256:bbvrvnmzcotbulq3zo7cdakl3vacysgap6il37m4widvdp7vr353a"]
                ["fluree:commit:sha256:bs3bcu3rhzlidsdvg3jgvbplhzxxlevvhipxsnqvfk7em4juu2os"
                 :f/alias
                 "query/everything"]
                ["fluree:commit:sha256:bs3bcu3rhzlidsdvg3jgvbplhzxxlevvhipxsnqvfk7em4juu2os"
                 :f/branch
                 "main"]
                ["fluree:commit:sha256:bs3bcu3rhzlidsdvg3jgvbplhzxxlevvhipxsnqvfk7em4juu2os"
                 :f/context
                 "fluree:memory:///contexts/b6dcf8968183239ecc7a664025f247de5b7859ac18cdeaace89aafc421eeddee"]]
              @(fluree/query db* {:select ['?s '?p '?o]
                                  :where  [['?s '?p '?o]]}) )))))))
