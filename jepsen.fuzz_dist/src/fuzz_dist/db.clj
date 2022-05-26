(ns fuzz-dist.db
  (:require [aleph.http :as http]
            [clojure.tools.logging :refer :all]
            [fuzz-dist
             [client :as fd-client]
             [util :as util]]
            [jepsen
             [db :as db]
             [control :as c]]
            [jepsen.control
             [scp :as scp]
             [util :as cu]]
            [manifold.stream :as s]))

(defn db
  "AntidoteDB."
  [version]
  (reify db/DB
    (setup! [this test node]
      ;; cp antidote from control to node to install
      (scp/scp! {:port 22 :private-key-path "~/.ssh/id_rsa"}
                [(:db-dir test)]
                (str "root" "@" node ":" "/root"))

      ;; cp fuzz_dist from control to node to install
      (scp/scp! {:port 22 :private-key-path "~/.ssh/id_rsa"}
                [(:fuzz-dist-dir test)]
                (str "root" "@" node ":" "/root"))

      (db/start! this test node)

      (Thread/sleep 10000))

    (teardown! [this test node]
      (db/kill! this test node)

      (c/su
       (c/exec :rm :-rf "/root/antidote")
       (c/exec :rm :-rf "/root/fuzz_dist")
       (c/exec :rm :-f  "/root/.erlang.cookie")))

    db/Primary
    (primaries [db test]
      (:nodes test))
    (setup-primary! [db test node]
      (let [conn @(http/websocket-client (fd-client/node-url node))]
      ;; TODO test type: :ok response
        (info ":db :setup_primary return:"
              (fd-client/ws-invoke conn
                                   :db
                                   :setup_primary (util/nodes-to-fqdn (:nodes test) "antidote")
                                   60000))
        (s/close! conn)))

    db/LogFiles
    (log-files [db test node]
    ;; Log file directories and names will change based on usage of start-daemon! 
      {util/node-fuzz-dist-log-file            "fuzz_dist_daemon"
       util/node-antidote-log-file             "antidote_daemon"
       "/root/antidote/logger_logs/errors.log" "antidote_logger_errors"
       "/root/antidote/logger_logs/info.log"   "antidote_logger_info"})

    db/Process
    (start! [this test node]
    ;; Antidote,  Erlang, uses :forground to match start-daemon! semantics. 
    ;; fuzz_dist, Elixir, uses :start     to match start-daemon! semantics.
    ;; (Also keep db/LogFiles in sync.)
      (if (cu/daemon-running? util/node-antidote-pid-file)
        :already-running
        (do
          (c/su
           (cu/start-daemon!
            {:chdir util/node-antidote
             :env {:NODE_NAME (util/n-to-fqdn node "antidote")
                   :COOKIE "antidote"}
             :logfile util/node-antidote-log-file
             :pidfile util/node-antidote-pid-file}
            "bin/antidote"
            :foreground))
          :restarted))

      (if (cu/daemon-running? util/node-fuzz-dist-pid-file)
        :already-running
        (do
          (cu/start-daemon!
           {:chdir util/node-fuzz-dist
            :env {:NODE_NAME (util/n-to-fqdn node "fuzz_dist")
                  :COOKIE "fuzz_dist"}
            :logfile util/node-fuzz-dist-log-file
            :pidfile util/node-fuzz-dist-pid-file}
           "bin/fuzz_dist"
           :start)
          :restarted)))

    (kill! [this test node]
      (c/su
       (cu/stop-daemon! util/node-fuzz-dist-pid-file)
       (cu/grepkill! "antidote")
       (cu/stop-daemon! util/node-antidote-pid-file)
       (cu/grepkill! "fuzz_dist")))

    db/Pause
    (pause! [this test node]
      (c/su
       (cu/grepkill! :stop :fuzz_dist)
       (cu/grepkill! :stop :antidote)))

    (resume! [this test node]
      (c/su
       (cu/grepkill! :cont :antidote)
       (cu/grepkill! :cont :fuzz_dist)))))
