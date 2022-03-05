(ns jepsen.fuzz_dist
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :refer :all]
            [aleph.http :as http]
            [manifold.stream :as s]
            [jepsen [cli :as cli]
             [client :as client]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn db
  "AntidoteDB for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "installing AntidoteDB" version)
      (c/exec :cp :-r "/var/jepsen/shared/fuzz_dist" "/root")
      (c/exec "/root/fuzz_dist/bin/fuzz_dist" "daemon")
      (Thread/sleep 10000))

    ;; TODO: use Jepsen daemon, for now catch failures
    (teardown! [_ test node]
      (info node "tearing down AntidoteDB")
      (try
        (c/exec "/root/fuzz_dist/bin/fuzz_dist" "stop")
        (catch Exception e))
      (Thread/sleep 10000)
      (c/exec :rm :-rf "/root/fuzz_dist"))

    db/LogFiles
    (log-files [_ test node]
      ["/root/fuzz_dist/tmp/log"])))

(defn node-url
  "An HTTP url for connecting to a node's FuzzDist Elixir client."
  [node]
  (str "ws://" node ":8080" "/fuzz_dist/jepsir"))

(defn ws-invoke
  "Invokes the op over the ws connection.
  On the BEAM side a :cowboy_websocket_handler dispatches to an Elixir @behavior."
  [conn op]
  ;; (s/put! conn (json/write-str op))
  (s/put! conn "ping")
  op
  ;; @(s/take! conn)
  )

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (info node "Client/open" (node-url node))
    (assoc this :conn @(http/websocket-client (node-url node))))

  (setup! [this test])

  (invoke! [_ test op]
    (case (:f op)
      :read (assoc op :type :ok, :value (ws-invoke conn op))))

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
          :db         (db "v0.2.2")
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
