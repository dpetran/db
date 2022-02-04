(ns synchronize-node-dependencies
  (:require [cheshire.core :as json]))

(let [project   (-> (slurp "../package.json") (json/parse))
      flureenjs (-> (slurp "package.json") (json/parse))]
  {:project project
   :flureenjs flureenjs})
