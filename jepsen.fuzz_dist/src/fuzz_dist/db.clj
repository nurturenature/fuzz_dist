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
      (scp/scp! {:port 22 :private-key-path (str util/control-root "/" ".ssh/id_rsa")}
                [(str util/control-antidote "/" "_build/default/rel/antidote")]
                (str "root" "@" node ":" "/root"))

      ;; cp fuzz_dist from control to node to install
      (scp/scp! {:port 22 :private-key-path (str util/control-root "/" ".ssh/id_rsa")}
                [(str util/control-fuzz-dist "/" "_build/prod/rel/fuzz_dist")]
                (str "root" "@" node ":" "/root"))

      (db/start! this test node)

      (Thread/sleep 15000))

    (teardown! [this test node]
      (db/kill! this test node)

      (Thread/sleep 5000)

      (c/su
       (cu/grepkill! "antidote")
       (cu/grepkill! "fuzz_dist")
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
                                   :setup_primary (util/nodes-to-fqdn (:nodes test) "antidote")))
        (s/close! conn)))

    db/LogFiles
    (log-files [db test node]
      {util/node-fuzz-dist-log-file "fuzz_dist_daemon"
       "/root/fuzz_dist/tmp/log"    "fuzz_dist_log"
       util/node-antidote-log-file  "antidote_daemon"
       "/root/antidote/logger_logs" "antidote_logger_logs"
       "/root/antidote/log"         "antidote_log"})

    db/Process
    (start! [this test node]

      (c/su
       (cu/start-daemon!
        {:chdir util/node-antidote
         :env {:NODE_NAME (util/n-to-fqdn node "antidote")
               :COOKIE "antidote"}
         :logfile util/node-antidote-log-file
         :pidfile util/node-antidote-pid-file}
        "bin/antidote"
        :start)

       (cu/start-daemon!
        {:chdir util/node-fuzz-dist
         :env {:NODE_NAME (util/n-to-fqdn node "fuzz_dist")
               :COOKIE "fuzz_dist"}
         :logfile util/node-fuzz-dist-log-file
         :pidfile util/node-fuzz-dist-pid-file}
        "bin/fuzz_dist"
        :start)))

    (kill! [this test node]
      (c/su
       (cu/stop-daemon! util/node-fuzz-dist-pid-file)
       (cu/stop-daemon! util/node-antidote-pid-file)))

    db/Pause
    (pause! [this test node]
      (c/su
       (cu/grepkill! :stop :fuzz_dist)
       (cu/grepkill! :stop :antidote)))

    (resume! [this test node]
      (c/su
       (cu/grepkill! :cont :antidote)
       (cu/grepkill! :cont :fuzz_dist)))))
