(ns fuzz-dist.nemesis
  (:require
   [fuzz-dist.db :as db]
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

;; TODO: net/drop! stops test?!?
;; nemesis crash, signature mismatch?
;; (defn dc2dc-nemesis
;;   "A nemesis that creates a network failure between 2 random datacenters."
;;   []
;;   (reify nemesis/Nemesis
;;     (setup! [this test] this)

;;     (invoke! [this test op]
;;       (case (:f op)
;;         :start-dc2dc-net-fail (net/drop! (:net test)
;;                                          test
;;                                          (rand-nth (:nodes test))
;;                                          (rand-nth (:nodes test)))
;;         :stop-dc2dc-net-fail (net/heal! (:net test)
;;                                         test)))

;;     (teardown! [this test])))

(defn full-nemesis
  "Merges together all nemeses"
  [opts]
  (nemesis/compose
   {{:start-maj-min  :start
     :stop-maj-min   :stop} (nemesis/partition-random-halves)
    {:start-maj-ring :start
     :stop-maj-ring  :stop} (nemesis/partition-majorities-ring)
    {:start-bridge :start
     :stop-bridge  :stop}   (nemesis/partitioner (comp nemesis/bridge shuffle))
    {:start-isolated :start
     :stop-isolated  :stop} (nemesis/partition-random-node)
    ;; #{:start-dc2dc-net-fail
    ;;   :stop-dc2dc-net-fail} (dc2dc-nemesis)

    {:start-restart :start
     :stop-restart :stop} (nemesis/node-start-stopper
                           rand-nth
                           (fn start [test node] (db/stop!  test node))
                           (fn stop  [test node] (db/start! test node)))

    #{:start-quiesce
      :stop-quiesce
      :final-read} (noop-nemesis)}))

(def bright-pink        "#FF6786")
(def pale-pink          "#F9D1D5")
(def cherub             "#F6CCEB")
(def brilliant-lavender "#EABCF5")
(def mauve              "#E1BFFF")
(def cosmos             "#FFD6D6")
(def paris-daisy        "#FFF378")
(def portafino          "#FAFFB0")
(def periwinkle         "#D0DDFF")
(def reef               "#C0FFB6")

(defn full-perf []
  {:nemeses #{{:name "maj/min partition"
               :start #{:start-maj-min}
               :stop  #{:stop-maj-min}
               :color bright-pink}
              {:name "maj rings partition"
               :start #{:start-maj-ring}
               :stop  #{:stop-maj-ring}
               :color pale-pink}
              {:name "bridge partition"
               :start #{:start-bridge}
               :stop  #{:stop-bridge}
               :color cherub}
              {:name "isolated dc"
               :start #{:start-isolated}
               :stop  #{:stop-isolated}
               :color cosmos}
              ;; {:name "dc2dc net fail"
              ;;  :start #{:start-dc2dc-net-fail}
              ;;  :stop  #{:stop-dc2dc-net-fail}
              ;;  :color paris-daisy}
              {:name "node restart"
               :start #{:start-restart}
               :stop  #{:stop-restart}
               :color brilliant-lavender}
              {:name "quiesce"
               :start #{:start-quiesce}
               :stop  #{:stop-quiesce}
               :color periwinkle}
              {:name "final read"
               :fs #{:final-read}
               :color reef}}})