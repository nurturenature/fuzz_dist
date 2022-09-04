(ns fuzz-dist.checker.final-reads
  "Checks a set-full history for `:final? true` reads and
  analyzes their consistency against all `:ok :add`'s."
  (:require [clojure.set :refer [difference union]]
            [jepsen.checker :as checker]
            [knossos.op :as op]))

(defn checker
  []
  (reify checker/Checker
    (check [_this {:keys [nodes] :as _test} history _opts]
      (let [final-reads (->> history
                             (filter (comp #{:read} :f))
                             (filter :final?)
                             (filter op/ok?)
                             (reduce (fn [acc {:keys [node value] :as _read}]
                                       (assoc acc node (->> value (set))))
                                     {}))
            all-add-values (->> history
                                (filter (comp #{:add} :f))
                                (filter op/ok?)
                                (map :value)
                                (set))
            missing-values (->> nodes
                                (reduce (fn [acc node]
                                          (if-let [final-read (get final-reads node)]
                                            (let [missing (difference all-add-values final-read)]
                                              (if (empty? missing)
                                                acc
                                                (assoc acc node (sort missing))))
                                            (assoc acc node :missing-final-read)))
                                        {}))]
        (cond-> {:valid? true}
          (seq missing-values)
          (assoc :valid? false
                 :missing missing-values)

          (not= (vals final-reads))
          (assoc :valid? false
                 :error  :unequal-final-reads))))))
