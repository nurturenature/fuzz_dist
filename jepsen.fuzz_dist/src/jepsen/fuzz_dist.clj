(ns jepsen.fuzz_dist
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer :all]
            [aleph.http :as http]
            [cheshire.core :as json]
            [manifold.stream :as s]
            [jepsen [cli :as cli]
             [client :as client]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.control.scp :as scp]
            [jepsen.os.debian :as debian]))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

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

  (json/parse-string @(s/try-take! conn 5000) true))

(def control_root "/home/jsuttor")
(def control_proj (str control_root "/" "projects"))
(def control_antidote (str control_proj "/" "antidote"))
(def control_fuzz_dist (str control_proj "/" "fuzz_dist" "/" "beam.fuzz_dist"))
(def node_antidote "/root/antidote")
(def node_fuzz_dist "/root/fuzz_dist")

(defn db
  "AntidoteDB."
  [version]
  (reify db/DB
    (setup! [_ test node]
      ;; cp from control to node to install
      (scp/scp! {:port 22 :private-key-path (str control_root "/" ".ssh/id_rsa")}
        [(str control_antidote "/" "_build/default/rel/antidote")]
        (str "root" "@" node ":" "/root"))
      
      (c/cd node_antidote
        (c/exec
          (c/env {:NODE_NAME (str "antidote@" node)
                  :COOKIE "antidote"})
          "bin/antidote"
          "start"))

      ;; cp from control to node to install
      (scp/scp! {:port 22 :private-key-path (str control_root "/" ".ssh/id_rsa")}
        [(str control_fuzz_dist "/" "_build/prod/rel/fuzz_dist")]
        (str "root" "@" node ":" "/root"))

        (c/cd node_fuzz_dist
          (c/exec
            (c/env {:NODE_NAME (str "fuzz_dist@" node)
                    :COOKIE "fuzz_dist"})
            "bin/fuzz_dist"
            "daemon"))

      (Thread/sleep 5000))

    (teardown! [_ test node]
      (cu/grepkill! "antidote")
      (cu/grepkill! "fuzz_dist")
      (c/exec :rm :-rf "/root/antidote")
      (c/exec :rm :-rf "/root/fuzz_dist")

      (Thread/sleep 5000))

    db/Primary
    (primaries [db test]
      (:nodes test))
    (setup-primary! [db test node]
      (let [conn @(http/websocket-client (node-url node))]
            (ws-invoke conn :db :setup_primary (:nodes test))
            (s/close! conn)))

    db/LogFiles
    (log-files [db test node]
      {"/root/fuzz_dist/tmp/log" "fuzz_dist_logs"
       "/root/antidote/logger_logs" "antidote_logs"
       })))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (info node "Client/open" (node-url node))
    (assoc this :conn @(http/websocket-client (node-url node))))

  (setup! [this test])

  (invoke! [_ test op]
    (case (:f op)
      :read (let [resp (ws-invoke conn :g_set :read op)]
              (case (:type resp)
                "fail" (assoc op :type :fail, :error (:return resp))))))

    ;;   :read (assoc op :type :ok, :value (ws-invoke conn op))))


  (teardown! [this test])

  (close! [_ test]
    (s/close! conn)))

(defn fuzz_dist-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
                      :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         opts
         {:name       "fuzz_dist"
          :os         debian/os
          :db         (db :git)
          :client     (Client. nil)
          :generator  (->> r
                           (gen/stagger 1)
                           (gen/nemesis nil)
                           (gen/time-limit 15))}))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
                browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn fuzz_dist-test})
                   (cli/serve-cmd))
            args))
