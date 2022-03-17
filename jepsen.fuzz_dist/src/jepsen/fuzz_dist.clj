(ns jepsen.fuzz-dist
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer :all]
            [aleph.http :as http]
            [cheshire.core :as json]
            [manifold.stream :as s]
            [jepsen [cli :as cli]
             [checker :as checker]
             [client :as client]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [nemesis :as nemesis]
             [tests :as tests]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util :as cu]
            [jepsen.control.scp :as scp]
            [jepsen.os.debian :as debian]))

(defn g-set-read [_ _] {:type :invoke, :f :read, :value nil})
(defn g-set-add  [_ _] {:type :invoke, :f :add,  :value (str (rand-int 1000000))})

(def control-root "/home/jsuttor")
(def control-proj (str control-root "/" "projects"))
(def control-antidote (str control-proj "/" "antidote"))
(def control-fuzz-dist (str control-proj "/" "fuzz_dist" "/" "beam.fuzz_dist"))
(def node-antidote "/root/antidote")
(def node-fuzz-dist "/root/fuzz_dist")

(defn n-to-fqdn [node app] (let [[n num] node]
                             (str app "@" "192.168.122.10" num)))
(defn nodes-to-fqdn [nodes app] (map #(n-to-fqdn % app) nodes))

(defn node-url
  "An HTTP url for connecting to a node's FuzzDist Elixir client."
  [node]
  (str "ws://" node ":8080" "/fuzz_dist/jep_ws"))

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

(defn db
  "AntidoteDB."
  [version]
  (reify db/DB
    (setup! [_ test node]
      ;; cp from control to node to install
      (scp/scp! {:port 22 :private-key-path (str control-root "/" ".ssh/id_rsa")}
                [(str control-antidote "/" "_build/default/rel/antidote")]
                (str "root" "@" node ":" "/root"))

      (c/cd node-antidote
            (c/exec
             (c/env {:NODE_NAME (n-to-fqdn node "antidote")
                     :COOKIE "antidote"})
             "bin/antidote"
             "daemon"))

      ;; cp from control to node to install
      (scp/scp! {:port 22 :private-key-path (str control-root "/" ".ssh/id_rsa")}
                [(str control-fuzz-dist "/" "_build/prod/rel/fuzz_dist")]
                (str "root" "@" node ":" "/root"))

      (c/cd node-fuzz-dist
            (c/exec
             (c/env {:NODE_NAME (n-to-fqdn node "fuzz_dist")
                     :COOKIE "fuzz_dist"})
             "bin/fuzz_dist"
             "daemon"))

      (Thread/sleep 15000))

    (teardown! [_ test node]
      (try
        (c/cd node-antidote
              (c/exec
               (c/env {:NODE_NAME (n-to-fqdn node "antidote")
                       :COOKIE "antidote"})
               "bin/antidote"
               "stop"))
        (c/cd node-fuzz-dist
              (c/exec
               (c/env {:NODE_NAME (n-to-fqdn node "fuzz_dist")
                       :COOKIE "fuzz_dist"})
               "bin/fuzz_dist"
               "stop"))
        (catch Exception e))

      (Thread/sleep 5000)

      (cu/grepkill! "antidote")
      (cu/grepkill! "fuzz_dist")
      (c/exec :rm :-rf "/root/antidote")
      (c/exec :rm :-rf "/root/fuzz_dist")
      (c/exec :rm :-f  "/root/.erlang.cookie"))

    db/Primary
    (primaries [db test]
      (:nodes test))
    (setup-primary! [db test node]
      ;; TODO Antidote wants FQDN, map n# to ip and pass ip's
      (let [conn @(http/websocket-client (node-url node))]
      ;; TODO test type: :ok response
        (info ":db :setup_primary return:" (ws-invoke conn
                                                      :db
                                                      :setup_primary (nodes-to-fqdn (:nodes test) "antidote")))
        (s/close! conn)))

    db/LogFiles
    (log-files [db test node]
      {"/root/fuzz_dist/tmp/log"    "fuzz_dist_log"
       "/root/antidote/logger_logs" "antidote_logger_logs"
       "/root/antidote/log"         "antidote_log"})))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (info node "Client/open" (node-url node))
    (assoc this :conn @(http/websocket-client (node-url node))))

  (setup! [this test])

  (invoke! [_ test op]
    (case (:f op)
      :add (let [resp (ws-invoke conn :g_set :add op)]
             (case (:type resp)
               "ok"   (assoc op :type :ok)
               "fail" (assoc op :type :fail, :error (:error resp))))
      :read (let [resp (ws-invoke conn :g_set :read op)]
              (case (:type resp)
                "ok"   (assoc op :type :ok,   :value (:value resp))
                "fail" (assoc op :type :fail, :error (:error resp))))))

  (teardown! [this test])

  (close! [_ test]
    (s/close! conn)))

(defn full-nemesis
  "Merges together all nemeses"
  [opts]
  (nemesis/compose
   {{:start-maj-min  :start
     :stop-maj-min   :stop} (nemesis/partition-random-halves)
    {:start-isolated :start
     :stop-isolated  :stop} (nemesis/partition-random-node)}))

(defn rnd-sleep    [] (+ 1 (rand-int 2)))
(defn rnd-duration [] (+ 2 (rand-int 5)))

(defn fuzz-dist-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
                      :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         opts
         {:name       "fuzz-dist"
          :os         debian/os
          :db         (db :git)
          :client     (Client. nil)
          :nemesis    (full-nemesis opts)
          :generator  (gen/phases
                       (->>
                        (gen/mix [g-set-read g-set-add])
                        (gen/stagger 1/10)
                        (gen/nemesis (gen/cycle
                                      (gen/phases
                                       (gen/sleep (rnd-sleep))
                                       {:type :info, :f :start-maj-min}
                                       (gen/sleep (rnd-duration))
                                       {:type :info, :f :stop-maj-min}
                                       (gen/sleep (rnd-sleep))

                                       (gen/sleep (rnd-sleep))
                                       {:type :info, :f :start-isolated}
                                       (gen/sleep (rnd-duration))
                                       {:type :info, :f :stop-isolated}
                                       (gen/sleep (rnd-sleep)))))

                        (gen/time-limit (or (:time-limit opts) 60)))

                       (gen/nemesis {:type :info, :f :stop-maj-min})
                       (gen/nemesis {:type :info, :f :stop-isolated})
                       (gen/log "Let database quiesce...")
                       (gen/sleep 5)

                       (gen/clients (gen/each-thread {:type :invoke :f :read :value nil})))
          :checker   (checker/compose
                      {:perf       (checker/perf {:nemeses #{{:name "partition"
                                                              :start #{:start-maj-min}
                                                              :stop  #{:stop-maj-min}
                                                              :color "#E9DCA0"}
                                                             {:name "isolated dc"
                                                              :start #{:start-isolated}
                                                              :stop  #{:stop-isolated}
                                                              :color "#E9A4A0"}}})
                       :set-full   (checker/set-full)
                       :timeline   (timeline/html)
                       :exceptions (checker/unhandled-exceptions)
                       :stats      (checker/stats)})}))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
                browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn fuzz-dist-test})
                   (cli/serve-cmd))
            args))
