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
  (s/put! conn
          (json/generate-string
           {:mod mod
            :fun fun
            :args op}))

  (json/parse-string @(s/take! conn) true))

  (defn db
  "AntidoteDB for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info "DB/setup" "AntidoteDB" node version)
      (scp/scp! {:port 22 :private-key-path "/home/jsuttor/.ssh/id_dsa"}
        '("/home/jsuttor/projects/antidote/_build/default/rel/antidote")
        (str "root" "@" node ":" "/root"))
      
      (c/cd "/root/antidote"
        (c/exec
          (c/env {:NODE_NAME (str "antidote@" node)
                  :COOKIE "antidote"})
          "bin/antidote"
          "start"))

      ;; (cu/start-daemon!
      ;;   {:env {:NODE_NAME (str "antidote@" node)
      ;;          :COOKIE "antidote"}
      ;;    :chdir "/root/antidote"}
      ;;   "bin/antidote"
      ;;   :foreground
      ;;   )

      ;; (c/exec :cp :-r "/home/jsuttor/projects/fuzz_dist/_build/prod/rel/fuzz_dist" "/root")
      (scp/scp! {:port 22 :private-key-path "/home/jsuttor/.ssh/id_dsa"}
        '("/home/jsuttor/projects/fuzz_dist/_build/prod/rel/fuzz_dist")
        (str "root" "@" node ":" "/root"))
      (c/exec "/root/fuzz_dist/bin/fuzz_dist" "daemon")

      (Thread/sleep 5000))

    ;; TODO: use Jepsen daemon, for now catch failures
    (teardown! [_ test node]
      (info node "tearing down AntidoteDB")
      (try
        ;; (c/exec "/root/fuzz_dist/bin/fuzz_dist" "stop")
        (catch Exception e))
      (cu/stop-daemon! "empd" nil)
      (cu/stop-daemon! "beam.smp" nil)
      ;; (try
      ;;   (c/exec "/root/antidote/bin/antidote" "stop")
      ;;   (catch Exception e))
      (Thread/sleep 5000)
      ;; (c/exec :rm :-rf "/root/fuzz_dist")
      ;; (c/exec :rm :-rf "/root/antidote"))
    )

    db/Primary
    (primaries [db test]
      (:nodes test))
    (setup-primary! [db test node]
      (let [conn @(http/websocket-client (node-url node))]
            (ws-invoke conn :db :setup_primary (:nodes test))))

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
    ;; TODO: close ws
    ))

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
