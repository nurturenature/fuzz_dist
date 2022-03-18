(ns fuzz-dist.nemesis
  (:require
   [jepsen.nemesis :as nemesis]
   [jepsen.net :as net]))

(defn noop-nemesis
  "A nemesis that does nothing."
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)
    (invoke! [this test op]
      (assoc op :value :noop))
    (teardown! [this test])))

(defn dc2dc-nemesis
  "A nemesis that creates a network failure between 2 random datacenters."
  []
  (reify nemesis/Nemesis
    (setup! [this test] this)

    (invoke! [this test op]
      (case (:f op)
        :start-dc2dc-net-fail (net/drop! (:net test)
                                         test
                                         "n1"
                                         "n2")
                                         ;; TODO: exception casting String to Associative?
                                         ;;(rand-nth (:nodes test))
                                         ;;(rand-nth (:nodes test)))
        :stop-dc2dc-net-fail (net/heal! (:net test)
                                        test)))

    (teardown! [this test])))

(defn full-nemesis
  "Merges together all nemeses"
  [opts]
  (nemesis/compose
   {{:start-maj-min  :start
     :stop-maj-min   :stop} (nemesis/partition-random-halves)
    {:start-isolated :start
     :stop-isolated  :stop} (nemesis/partition-random-node)
    {:start-dc2dc-net-fail :start
     :stop-dc2dc-net-fail  :stop} (nemesis/partitioner {"n1" ["n2"] "n2" ["n1"]})
    #{:start-quiesce
      :stop-quiesce
      :final-read} (noop-nemesis)}))
