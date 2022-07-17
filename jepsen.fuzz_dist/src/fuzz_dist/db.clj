(ns fuzz-dist.db
  (:require [clojure.tools.logging :refer [info]]
            [fuzz-dist
             [client :as fd-client]]
            [jepsen
             [db :as db]
             [control :as c]]
            [jepsen.control
             [scp :as scp]
             [util :as cu]]
            [slingshot.slingshot :refer [throw+]]))

(def node-antidote          "/root/antidote")
(def antidote-log-file      "antidote.daemon.log")
(def node-antidote-log-file (str node-antidote "/" antidote-log-file))
(def node-antidote-pid-file (str node-antidote "/" "antidote.daemon.pid"))
(def node-fuzz-dist          "/root/fuzz_dist")
(def fuzz-dist-log-file      "fuzz_dist.daemon.log")
(def node-fuzz-dist-log-file (str node-fuzz-dist "/" fuzz-dist-log-file))
(def node-fuzz-dist-pid-file (str node-fuzz-dist "/" "fuzz_dist.daemon.pid"))

(defn n-to-fqdn
  "Erlang wants at least one . in a fully qualified hostname,
  so add one to the end. Assuming we are in a LXC/Docker environment with names like n1."
  [node app] (str app "@" node "."))
(defn nodes-to-fqdn [nodes app] (map #(n-to-fqdn % app) nodes))

(defn db
  "AntidoteDB."
  [_version]
  (reify db/DB
    (setup! [this {:keys [db-dir erlang-eval fuzz-dist-dir] :as test} node]
      ;; cp antidote from control to node to install
      (scp/scp! {:port 22 :private-key-path "~/.ssh/id_rsa"}
                [db-dir]
                (str "root" "@" node ":" "/root"))

      ;; cp fuzz_dist from control to node to install
      (scp/scp! {:port 22 :private-key-path "~/.ssh/id_rsa"}
                [fuzz-dist-dir]
                (str "root" "@" node ":" "/root"))

      (db/start! this test node)

      (Thread/sleep 10000)
      (when erlang-eval
        (let [resp (c/su
                    (c/cd
                     node-antidote
                     (c/exec
                      (c/env {:NODE_NAME (n-to-fqdn node "antidote")
                              :COOKIE "antidote"})
                      "bin/antidote"
                      :eval
                      erlang-eval)))]
          (info "erlang-eval: " erlang-eval " -> " resp))))

    (teardown! [this test node]
      (db/kill! this test node)

      (c/su
       (c/exec :rm :-rf "/root/antidote")
       (c/exec :rm :-rf "/root/fuzz_dist")
       (c/exec :rm :-f  "/root/.erlang.cookie")))

    db/Primary
    ; Antidote doesn't have the concept of primary nodes.
    ; For Jepsen semantics, all Antidote nodes can be primaries.
    (primaries [_db test]
      (:nodes test))

    (setup-primary! [_db test node]
      ;; Setup Antidote by clustering data centers.
      (info "Clustering Antidote: " {:topology (:topology test)
                                     :nodes (nodes-to-fqdn (:nodes test) "antidote")})
      (let [conn (fd-client/get-ws-conn fd-client/node-url node)]
        (when (->>
               (fd-client/ws-invoke conn
                                    :db
                                    :setup_primary {:topology (:topology test)
                                                    :nodes (nodes-to-fqdn (:nodes test) "antidote")}
                                    60000)
               (:type)
               (not= "ok"))
          (throw+ [:type ::setup-failed]))
        (fd-client/ws-close conn)))

    db/LogFiles
    (log-files [_db _test _node]
      {node-fuzz-dist-log-file fuzz-dist-log-file
       node-antidote-log-file  antidote-log-file
       (str node-antidote "/" "logger_logs/errors.log") "antidote_logger_errors.log"
       (str node-antidote "/" "logger_logs/info.log")   "antidote_logger_info.log"})

    db/Kill
    ; Antidote,  Erlang, uses :forground to match start-daemon! semantics. 
    ; fuzz_dist, Elixir, uses :start     to match start-daemon! semantics.
    ; Erlang process name is beam.smp
    (start! [_this test node]
      (if (cu/daemon-running? node-antidote-pid-file)
        :already-running
        (do
          (c/su
           (cu/start-daemon!
            {:chdir node-antidote
             :env {:NODE_NAME (n-to-fqdn node "antidote")
                   :COOKIE "antidote"}
             :logfile node-antidote-log-file
             :pidfile node-antidote-pid-file}
            "bin/antidote"
            :foreground
            :-kernel   :logger_level (:kernel-logger-level test)
            :-antidote :sync_log     (:antidote-sync-log?  test)))
          :restarted))

      (if (cu/daemon-running? node-fuzz-dist-pid-file)
        :already-running
        (c/su
         (cu/start-daemon!
          {:chdir node-fuzz-dist
           :env {:NODE_NAME (n-to-fqdn node "fuzz_dist")
                 :COOKIE "fuzz_dist"}
           :logfile node-fuzz-dist-log-file
           :pidfile node-fuzz-dist-pid-file}
          "bin/fuzz_dist"
          :start)
         :restarted)))

    (kill! [_this _test _node]
      (c/su
       (cu/stop-daemon! node-fuzz-dist-pid-file)
       (cu/stop-daemon! node-antidote-pid-file)
       (cu/grepkill! "beam.smp")))

    db/Pause
    (pause! [_this _test _node]
      (c/su
       (cu/grepkill! :stop "beam.smp")))

    (resume! [_this _test _node]
      (c/su
       (cu/grepkill! :cont "beam.smp")))))
