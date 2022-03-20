(ns fuzz-dist.core
  (:require
   [aleph.http :as http]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.tools.logging :refer :all]
   [fuzz-dist [db :as db]
    [nemesis :as nemesis]
    [util :as util]]
   [jepsen [cli :as cli]
    [checker :as checker]
    [client :as client]
    [generator :as gen]
    [tests :as tests]]
   [jepsen.checker.timeline :as timeline]
   [jepsen.os.debian :as debian]
   [manifold.stream :as s]))

(defn g-set-read [_ _]
  (repeat {:type :invoke, :f :read, :value nil}))
(defn g-set-add  [_ _]
  (map (fn [x] {:type :invoke, :f :add, :value (str x)}) (range)))

(defn ws-invoke
  "Invokes the op over the ws connection.
  On the BEAM side a :cowboy_websocket_handler dispatches to an Elixir @behavior."
  [conn mod fun op]
  (s/try-put! conn
              (json/generate-string
               {:mod mod
                :fun fun
                :args op})
              5000)

  (json/parse-string @(s/try-take! conn 60000) true)
  ;; TODO: catch timeout
  )

(defrecord GSetClient [conn]
  client/Client
  (open! [this test node]
    (info node "Client/open" (util/node-url node))
    (assoc this :conn @(http/websocket-client (util/node-url node))))

  (setup! [this test])

  (invoke! [_ test op]
    (case (:f op)
      :add (let [resp (ws-invoke conn :g_set :add op)]
             (case (:type resp)
               "ok"   (assoc op :type :ok)
               "fail" (assoc op :type :fail, :error (:error resp))))
      :read (let [resp (ws-invoke conn :g_set :read op)]
              (case (:type resp)
                ;; sort returned set for human readability, json unorders
                "ok"   (assoc op :type :ok,   :value (sort (:value resp)))
                "fail" (assoc op :type :fail, :error (:error resp))))))

  (teardown! [this test])

  (close! [_ test]
    (s/close! conn)))

(defn demo-workload
  "A generator, client, and checker for a set test."
  [opts]
  {:client  (GSetClient. nil)
   :nemesis (nemesis/full-nemesis opts)
   :checker (checker/compose
             {:perf       (checker/perf (nemesis/full-perf))
              :set-full   (checker/set-full)
              :timeline   (timeline/html)
              :exceptions (checker/unhandled-exceptions)
              :stats      (checker/stats)})
   :generator  (gen/phases
                (->>
                 (gen/mix [g-set-read g-set-add])
                 (gen/stagger (/ (:rate opts)))
                 (gen/nemesis (gen/cycle
                               (gen/phases
                                (gen/sleep (:nemesis-quiet opts))
                                {:type :info, :f :start-maj-min}
                                (gen/sleep (:nemesis-duration opts))
                                {:type :info, :f :stop-maj-min}
                                (gen/sleep (:nemesis-quiet opts))

                                (gen/sleep (:nemesis-quiet opts))
                                {:type :info, :f :start-maj-ring}
                                (gen/sleep (:nemesis-duration opts))
                                {:type :info, :f :stop-maj-ring}
                                (gen/sleep (:nemesis-quiet opts))

                                (gen/sleep (:nemesis-quiet opts))
                                {:type :info, :f :start-bridge}
                                (gen/sleep (:nemesis-duration opts))
                                {:type :info, :f :stop-bridge}
                                (gen/sleep (:nemesis-quiet opts))

                                (gen/sleep (:nemesis-quiet opts))
                                {:type :info, :f :start-isolated}
                                (gen/sleep (:nemesis-duration opts))
                                {:type :info, :f :stop-isolated}
                                (gen/sleep (:nemesis-quiet opts))

                              ;; TODO: in progress
                                ;; ;; extra sleep for node to stop/start
                                ;; (gen/sleep (:nemesis-quiet opts))
                                ;; {:type :info, :f :start-restart}
                                ;; (gen/sleep (:nemesis-duration opts))
                                ;; (gen/sleep (:nemesis-quiet opts))
                                ;; {:type :info, :f :stop-restart}
                                ;; (gen/sleep (:nemesis-duration opts))
                                ;; (gen/sleep (:nemesis-quiet opts))

                                ;; TODO: net/drop! not working
                                ;; (gen/sleep (:nemesis-quiet opts))
                                ;; {:type :info, :f :start-dc2dc-net-fail}
                                ;; (gen/sleep (:nemesis-duration opts))
                                ;; {:type :info, :f :stop-dc2dc-net-fail}
                                ;; (gen/sleep (:nemesis-quiet opts))
                                )))
                 (gen/time-limit (:time-limit opts)))

                (gen/nemesis {:type :info, :f :stop-maj-min})
                (gen/nemesis {:type :info, :f :stop-maj-ring})
                (gen/nemesis {:type :info, :f :stop-bridge})
                (gen/nemesis {:type :info, :f :stop-isolated})
                ;; (gen/nemesis {:type :info, :f :stop-dc2dc-net-fail})
                ;; TODO: :stop-restart state?
                (gen/log "Let database quiesce...")
                (gen/nemesis {:type :info, :f :start-quiesce})
                (gen/sleep 5)
                (gen/nemesis {:type :info, :f :stop-quiesce})

                (gen/log "Final read...")
                (gen/sleep 1)
                (gen/nemesis {:type :info, :f :final-read})
                (gen/clients (gen/each-thread {:type :invoke :f :read :value nil})))
   ;; TODO real generic final
   :final-generator (gen/once {:type :invoke, :f :read, :value nil})})

(def workloads
  "A map of workload names to functions that construct workloads, given opts."
  {"demo" demo-workload})

(defn fuzz-dist-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
                      :concurrency, ...), constructs a test map."
  [opts]
  (let [workload ((get workloads (:workload opts)) opts)]
    (merge tests/noop-test
           opts
           {:name       (str "fuzz-dist - Antidote - " (count (:nodes opts)) "-x-dc1n1 - g-set")
            :os         debian/os
            :db         (db/db :git)
            :client     (:client workload)
            :nemesis    (:nemesis workload)
            :pure-generators true
            :generator  (:generator workload)
            :checker    (:checker workload)})))

(def cli-opts
  "Additional command line options."
  [["-w" "--workload NAME" "What workload should we run?"
    :missing  (str "--workload " (cli/one-of workloads))
    :validate [workloads (cli/one-of workloads)]]
   [nil "--nemesis-quiet QT" "Quiet time both before and after nemesis activity."
    :default  2
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be a positive number"]]
   [nil "--nemesis-duration DT" "Duration time of nemesis activity."
    :default  5
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be a positive number"]]
   ["-r" "--rate HZ" "Approximate number of requests per second, per thread."
    :default  10
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be a positive number"]]])

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
                browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  fuzz-dist-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))
