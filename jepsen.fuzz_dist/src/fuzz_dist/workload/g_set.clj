(ns fuzz-dist.workload.g-set
  (:require [aleph.http :as http]
            [clojure.tools.logging :refer :all]
            [fuzz-dist
             [client :as fd-client]
             [nemesis :as nemesis]
             [util :as util]]
            [jepsen
             [checker :as checker]
             [client :as client]
             [generator :as gen]]
            [manifold.stream :as s]))

(defrecord GSetClient [conn]
  client/Client
  (open! [this test node]
    (info node "Client/open" (fd-client/node-url node))
    (assoc this :conn @(http/websocket-client (fd-client/node-url node))))

  (setup! [this test])

  (invoke! [_ test op]
    (case (:f op)
      :add (let [resp (fd-client/ws-invoke conn :g_set :add op)]
             (case (:type resp)
               "ok"   (assoc op :type :ok)
               "fail" (assoc op :type :fail, :error (:error resp))))
      :read (let [resp (fd-client/ws-invoke conn :g_set :read op)]
              (case (:type resp)
                ;; sort returned set for human readability, json unorders
                "ok"   (assoc op :type :ok,   :value (sort (:value resp)))
                "fail" (assoc op :type :fail, :error (:error resp))))))

  (teardown! [this test])

  (close! [_ test]
    (s/close! conn)))

(defn workload
  "Constructs a workload, {:client, :generator, :final-generator, :checker},
   for a g-set, given options from the CLI test constructor."
  [opts]
  {:client    (GSetClient. nil)
   :generator (gen/mix [(map (fn [x] {:type :invoke, :f :add, :value (str x)}) (drop 1 (range)))
                        (repeat {:type :invoke, :f :read, :value nil})])
   :final-generator (gen/phases
                     ;; a simple sequence of transactions to help clarify end state and final reads
                     (gen/log "Final adds in healed state...")
                     (gen/sleep 1)
                     (gen/clients (gen/each-thread {:type :invoke :f :read :value nil}))
                     (gen/sleep 1)
                     (gen/clients
                      (->>
                       (map (fn [x] {:type :invoke, :f :add, :value (str :final "-" x)}) (drop 1 (range)))
                       (gen/stagger (/ 1))
                       (gen/time-limit 5)))
                     (gen/sleep 1)

                     (gen/log "Let database quiesce...")
                     (gen/nemesis {:type :info, :f :start-quiesce})
                     (gen/sleep 5)
                     (gen/nemesis {:type :info, :f :stop-quiesce})

                     (gen/log "Final read...")
                     (gen/sleep 1)
                     (gen/nemesis {:type :info, :f :start-final-read})
                     (gen/clients (gen/each-thread {:type :invoke :f :read :value nil}))
                     (gen/nemesis {:type :info, :f :stop-final-read}))
   :checker (checker/set-full)})

(defn gen-rand-nemesis
  [opts]
  (let [nemesis          ((nemesis/all-nemeses
                           (rand-nth (seq (:faults opts)))) opts)
        [quiet-range,
         duration-range] (:faults-times opts)]
    (gen/phases
     (gen/sleep (util/rand-int-from-range quiet-range))
     {:type :info, :f (:start nemesis)}
     (gen/sleep (util/rand-int-from-range duration-range))
     {:type :info, :f (:stop nemesis)})))

(defn package
  "Constructs a package, {:nemesis, :generator, :final-generator, :perf},
   for a g-set, given options from the CLI test constructor."
  [opts]
  {:nemesis         (nemesis/some-nemesis (:faults opts) opts)
   :generator       (gen/cycle
                     (fn [] (gen-rand-nemesis opts)))
   :final-generator (gen/phases
                     ;; :stop all possible nemeses
                     (gen/log "Healing all nemeses...")
                     (map (fn [[_ nem]]
                            (let [nemesis (nem opts)]
                              (gen/nemesis {:type :info, :f (:stop nemesis)})))
                          (select-keys nemesis/all-nemeses (seq (:faults opts)))))

   :perf              (nemesis/some-perf (:faults opts) opts)})
