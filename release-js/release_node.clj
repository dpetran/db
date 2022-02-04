(ns sync-node-deps
  (:require [cheshire.core :as json]))

(let [project   (json/parse-string (slurp "package.json"))
      flureenjs (json/parse-string (slurp "release-js/package.json"))
      updated (-> flureenjs
                  (assoc "dependencies" (get project "dependencies")))]
  (spit "release-js/package.json"
        (json/generate-string updated {:pretty true})))
