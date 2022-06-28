(ns fuzz-dist.checker.final-reads
  "Checks a set-full history for :final? true reads and
  analyzes their consistency against all :ok :add's."
  (:require [clojure.set :refer [difference union]]
            [jepsen.checker :as checker]
            [knossos.op :as op]))

(defn checker
  []
  (reify checker/Checker
    (check [_this _test history _opts]
      (let [final-reads (->> history
                             (filter (comp #{:read} :f))
                             (filter :final?)
                             (filter op/ok?))
            all-add-values (->> history
                                (filter (comp #{:add} :f))
                                (filter op/ok?)
                                (map :value)
                                (set))
            missing-values (->> final-reads
                                (map :value)
                                (reduce
                                 #(->> (difference all-add-values (set %2))
                                       (union %1))
                                 #{}))]
        {:valid?        (empty? missing-values)
         :missing-count (count missing-values)
         :missing       (->> (seq missing-values)
                             (sort))}))))