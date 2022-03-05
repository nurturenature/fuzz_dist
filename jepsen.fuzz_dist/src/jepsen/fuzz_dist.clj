(ns jepsen.fuzz_dist
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen [cli :as cli]
             [client :as client]
             [control :as c]
             [db :as db]
             [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [aleph.http :as http]))

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
      (c/exec :rm :-rf "/root/fuzz_dist"))

    db/LogFiles
    (log-files [_ test node]
      ["/root/fuzz_dist/tmp/log"])))

(defn node-url
  "An HTTP url for connecting to a node's FuzzDist Elixir client."
  [node]
  (str "ws://" node ":8080" "/fuzz_dist/jepsen/client"))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (info node "Client/open" (node-url node))
    (assoc this :conn @(http/websocket-client (node-url node))))

  (setup! [this test])

  (invoke! [_ test op])

  (teardown! [this test])

  (close! [_ test]))

(defn fuzz_dist-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
                      :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         opts
         {:name "fuzz_dist"
          :os   debian/os
          :db   (db "v0.2.2")
          :client (Client. nil)
          :pure-generators true}))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
                browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn fuzz_dist-test})
                   (cli/serve-cmd))
            args))
