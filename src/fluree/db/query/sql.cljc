(ns fluree.db.query.sql
  (:require [fluree.db.query.sql.template :as template]
            [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [instaparse.core :as insta]
               :cljs [instaparse.core :as insta :refer-macros [defparser]])))

(def sql
  "Parses SQL query strings into hiccup-formatted BNF rule trees"
  (-> "sql-92.bnf"
      io/resource
      (insta/parser :input-format :ebnf)))


(defn rule-tag
  [r]
  (first r))

(defn rule?
  [elt]
  (and (sequential? elt)
       (keyword? (rule-tag elt))))

(def rules
  "Hierarchy of SQL BNF rule name keywords for parsing equivalence"
  (-> (make-hierarchy)
      (derive :column-name ::string)
      (derive :character-string-literal ::string)))

(defmulti rule-parser
  "Parse SQL BNF rules depending on their type. Returns a function whose return
  chain will eventually contain the parsed rule after repeated execution."
  rule-tag
  :hierarchy #'rules)

(defn parse-rule
  "Uses `trampoline` to allow for mutual recursion without consuming call stack
  space when executing the rule parser on deeply nested rule trees."
  [r]
  (-> r rule-parser trampoline))

(defn parse-element
  [e]
  (if (rule? e)
    (parse-rule e)
    e))

(defn parse-all
  [elts]
  (mapcat parse-element elts))

(defn parse-into-map
  [elts]
  (->> elts
       (group-by rule-tag)
       (reduce-kv (fn [rules tag lst]
                    (assoc rules tag (parse-all lst)))
                  {})))

(defn bounce
  "Returns a function that, when executed, returns the argument supplied to this
  function, `v`, wrapped in a vector if `v` is a rule or scalar, or it returns
  `v` itself if `v` is a list of rules or scalars. Used for mutually recursive
  parse function's return values in conjunction with `trampoline` to conserve
  call-stack space."
  [v]
  (if (and (sequential? v)
           (not (rule? v)))
    (constantly v)
    (constantly [v])))


(defmethod rule-parser :default
  [[_ & rst]]
  (->> rst parse-all bounce))


(defmethod rule-parser :unsigned-integer
  [[_ & rst]]
  (->> rst
       parse-all
       (apply str)
       Integer/parseInt
       bounce))


(defmethod rule-parser :double-quote
  [_]
  (bounce \"))


(defmethod rule-parser ::string
  [[_ & rst]]
  (->> rst
       parse-all
       (apply str)
       bounce))


(defmethod rule-parser :qualifier
  [[_ q]]
  (-> q
      parse-element
      first
      ::coll
      bounce))


(defmethod rule-parser :subject-placeholder
  [[_ _ & rst]]
  (bounce {::obj (->> rst
                      parse-all
                      (apply str)
                      str/capitalize
                      (str template/collection-var))}))


(defmethod rule-parser :unsigned-value-specification
  [[_ v]]
  (bounce {::obj (-> v parse-element first)}))


(defmethod rule-parser :column-reference
  [[_ & rst]]
  (let [parse-map (parse-into-map rst)
        pred      (some-> parse-map
                          :column-name
                          first
                          template/field->predicate-template)
        subject   (some-> parse-map
                          :subject-placeholder
                          first)
        coll      (-> parse-map
                      :qualifier
                      first)]

    (cond->> (or subject
                 {::subj template/collection-var
                  ::pred pred})
      coll     (template/fill-in-collection coll)
      :finally bounce)))


(defmethod rule-parser :set-quantifier
  [[_ quantifier]]
  (let [k  (if (= quantifier "DISTINCT") :selectDistinct :select)]
    (bounce k)))


(defmethod rule-parser :asterisk
  [_]
  (bounce {::select {template/collection-var ["*"]}}))


(defmethod rule-parser :select-list-element
  [[_ & rst]]
  (let [parse-map                (parse-into-map rst)
        {::keys [subj pred obj]} (->> parse-map :derived-column first)
        var                      (or (some-> pred template/build-var)
                                     obj)
        triple                   [subj pred var]]
    (cond->  {::select [var]}
      (template/predicate? pred) (assoc ::where [triple])
      :finally                   bounce)))


(defmethod rule-parser :select-list
  [[_ & rst]]
  (->> rst
       parse-all
       (apply merge-with into)
       bounce))


(defmethod rule-parser :between-predicate
  [[_ & rst]]
  (let [[col l u]  (->> rst
                        (filter rule?)
                        parse-all)
        pred       (::pred col)
        lower      (::obj l)
        upper      (::obj u)
        field-var  (template/build-var pred)
        selector   [template/collection-var pred field-var]
        refinement (if (some #{"NOT"} rst)
                            {:union [{:filter [(template/build-fn-call ["<" field-var lower])]}
                                     {:filter [(template/build-fn-call [">" field-var upper])]}]}
                            {:filter [(template/build-fn-call [">=" field-var lower])
                                      (template/build-fn-call ["<=" field-var upper])]})]
    (bounce [selector refinement])))


(defmethod rule-parser :comparison-predicate
  [[_ & rst]]
  (let [parse-map    (parse-into-map rst)
        comp         (-> parse-map :comp-op first)
        [left right] ( :row-value-constructor parse-map)]
    (bounce (cond
              (#{\=} comp) (cond
                             (or (::obj left)
                                 (::obj right))   (let [{::keys [subj pred obj]} (merge left right)]
                                                    [[subj pred obj]])
                             (and (::pred left)
                                  (::pred right)) (let [v (template/build-var (::pred right))]
                                                    [[(::subj right) (::pred right) v]
                                                     [(::subj left) (::pred left) v]]))

              #_#_(#{\> \<} comp) (let [field-var (template/build-var pred)
                                    filter-fn (template/build-fn-call [comp field-var v])]
                                [[template/collection-var pred field-var]
                                 {:filter [filter-fn]}])))))


(defmethod rule-parser :in-predicate
  [[_ & rst]]
  (let [parse-map      (->> rst
                            (filter (fn [e]
                                      (not (contains? #{"IN" "NOT"} e))))
                            parse-into-map)
        pred           (-> parse-map :row-value-constructor first ::pred)
        field-var      (template/build-var pred)
        selector       [template/collection-var pred field-var]
        not?           (some #{"NOT"} rst)
        filter-pred    (if not? "not=" "=")
        filter-junc    (if not? "and" "or")
        filter-clauses (->> parse-map
                            :in-predicate-value
                            (map ::obj)
                            (map (fn [v]
                                   (template/build-fn-call [filter-pred field-var v])))
                            (str/join " "))
        filter-func    (str "(" filter-junc " " filter-clauses ")")]
    (bounce [selector {:filter filter-func}])))


(defmethod rule-parser :null-predicate
  [[_ p & rst]]
  (let [pred      (-> p parse-element first ::pred)
        field-var (template/build-var pred)]
    (if (some #{"NOT"} rst)
      (bounce [[template/collection-var pred field-var]])
      (bounce [[template/collection-var "rdf:type" template/collection]
               {:optional [[template/collection-var pred field-var]]}
               {:filter [(template/build-fn-call ["nil?" field-var])]}]))))


(defmethod rule-parser :boolean-term
  [[_ & rst]]
  (->> rst
       (filter (partial not= "AND"))
       parse-all
       bounce))


(defmethod rule-parser :search-condition
  [[_ & rst]]
  (if (some #{"OR"} rst)
    (let [[front _ back] rst]
      (bounce {:union [(-> front parse-element vec)
                       (-> back parse-element vec)]}))
    (->> rst parse-all bounce)))


(defmethod rule-parser :table-name
  [[_ & rst]]
  (let [parsed-name (->> rst
                         parse-all
                         (apply str))]
    (bounce {::coll [parsed-name]})))


(defmethod rule-parser :from-clause
  [[_ _ & rst]]
  (->> rst
       parse-all
       (apply merge-with into)
       bounce))

(defmethod rule-parser :where-clause
  [[_ _ & rst]]
  (bounce {::where (->> rst parse-all vec)}))


(defmethod rule-parser :group-by-clause
  [[_ _ & rst]]
  (->> rst
       parse-all
       (map (comp template/build-var ::pred))
       bounce))


(defmethod rule-parser :table-expression
  [[_ & rst]]
  (let [parse-map    (parse-into-map rst)
        from-clause  (->> parse-map :from-clause first)
        where-clause (or (some->> parse-map :where-clause first)
                         {::where [[template/collection-var  "rdf:type" template/collection]]})
        grouping     (->> parse-map :group-by-clause vec)
        from         (-> from-clause ::coll first)]
    (-> (merge-with into from-clause where-clause)
        (assoc ::group grouping)
        (->> (template/fill-in-collection from))
        bounce)))


(defmethod rule-parser :query-specification
  [[_ _ & rst]]
  (let [parse-map               (parse-into-map rst)
        select-key              (-> parse-map
                                    :set-quantifier
                                    first
                                    (or :select))
        table-expr              (-> parse-map :table-expression first)
        select-list             (-> parse-map :select-list first)
        {::keys [coll select
                 where group]}  (merge-with into table-expr select-list)
        from                    (first coll)]

    (cond-> {select-key (template/fill-in-collection from select)
             :where     (template/fill-in-collection from where)
             ::coll     coll}
      (seq group) (assoc :opts {:groupBy group})
      :finally    bounce)))


(defmethod rule-parser :join-condition
  [[_ _ & rst]]
  (bounce {::where (->> rst parse-all vec)}))


(defmethod rule-parser :outer-join-type
  [[_ t]]
  (bounce (case t
            "LEFT"  ::left
            "RIGHT" ::right
            "FULL"  ::full)))


(defmethod rule-parser :join-type
  [[_ t & rst]]
  (bounce (case t
            "INNER" ::inner
            "UNION" ::union
            (parse-element t)))) ; `:outer-join-type` case


(defmethod rule-parser :named-columns-join
  [[_ _ & rst]]
  (->> rst parse-all bounce))


(defmethod rule-parser :qualified-join
  [[_ & rst]]
  (let [parse-map (->> rst
                       (filter rule?)
                       parse-into-map)
        spec      (->> parse-map
                       :join-specification
                       (apply merge-with into))
        join-ref  (->> parse-map
                       :table-reference
                       (apply merge-with into spec))
        join-type (-> parse-map
                      :join-type
                      first
                      (or ::inner))]
    (bounce (case join-type
              ::inner join-ref))))


(defmethod rule-parser :ordering-specification
  [[_ order]]
  (-> order str/upper-case bounce))


(defmethod rule-parser :sort-specification
  [[_ & rst]]
  (let [parse-map (parse-into-map rst)
        pred      (-> parse-map
                      :sort-key
                      first
                      template/field->predicate-template)]
    (if-let [order (some->> parse-map :ordering-specification first)]
      (bounce [[order pred]])
      (bounce pred))))


(defmethod rule-parser :order-by-clause
  [[_ _ & rst]]
  (->> rst parse-all bounce))


(defmethod rule-parser :direct-select-statement
  [[_ & rst]]
  (let [parse-map                 (parse-into-map rst)
        {::keys [coll] :as query} (->> parse-map :query-expression first)
        ordering                  (some->> parse-map
                                           :order-by-clause
                                           first
                                           (template/fill-in-collection (first coll)))]
    (cond-> query
      ordering  (update :opts assoc :orderBy ordering)
      :finally  bounce)))


(defn parse
  [q]
  (-> q
      sql
      parse-rule
      first
      (select-keys [:select :selectDistinct :selectOne :where :block :prefixes
                    :vars :opts])))
