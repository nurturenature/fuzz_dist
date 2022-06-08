(ns fuzz-dist.db
  (:require [clojure.tools.logging :refer :all]
            [fuzz-dist
             [client :as fd-client]]
            [jepsen
             [db :as db]
             [control :as c]]
            [jepsen.control
             [scp :as scp]
             [util :as cu]]
            [manifold.stream :as s])
  (:use [slingshot.slingshot :only [throw+]]))

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
      ;; Antidote doesn't have the concept of primary nodes.
      ;; For Jepsen semantics, all Antidote nodes can be primaries.
      (:nodes test))

    (setup-primary! [db test node]
      ;; Setup Antidote by clustering data centers.
      (info "Clustering Antidote: " {:topology (:topology test)
                                     :nodes (nodes-to-fqdn (:nodes test) "antidote")})
      (let [conn (fd-client/get-ws-conn fd-client/node-url node)]
        (if (->>
             (fd-client/ws-invoke conn
                                  :db
                                  :setup_primary {:topology (:topology test)
                                                  :nodes (nodes-to-fqdn (:nodes test) "antidote")}
                                  60000)
             (:type)
             (not= "ok"))
          (throw+ [:type ::setup-failed]))
        (s/close! conn)))

    db/LogFiles
    (log-files [db test node]
    ;; Log file directories and names will change based on usage of start-daemon! 
      {node-fuzz-dist-log-file fuzz-dist-log-file
       node-antidote-log-file  antidote-log-file
       (str node-antidote "/" "logger_logs/errors.log") "antidote_logger_errors.log"
       (str node-antidote "/" "logger_logs/info.log")   "antidote_logger_info.log"})

    db/Process
    (start! [this test node]
    ;; Antidote,  Erlang, uses :forground to match start-daemon! semantics. 
    ;; fuzz_dist, Elixir, uses :start     to match start-daemon! semantics.
    ;; (Also keep db/LogFiles in sync.)
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
            :foreground))
          :restarted))

      (if (cu/daemon-running? node-fuzz-dist-pid-file)
        :already-running
        (do
          (c/su
           (cu/start-daemon!
            {:chdir node-fuzz-dist
             :env {:NODE_NAME (n-to-fqdn node "fuzz_dist")
                   :COOKIE "fuzz_dist"}
             :logfile node-fuzz-dist-log-file
             :pidfile node-fuzz-dist-pid-file}
            "bin/fuzz_dist"
            :start)
           :restarted))))

    (kill! [this test node]
      (c/su
       (cu/stop-daemon! node-fuzz-dist-pid-file)
       (cu/grepkill! :antidote)
       (cu/stop-daemon! node-antidote-pid-file)
       (cu/grepkill! :fuzz_dist)))

    db/Pause
    (pause! [this test node]
      (c/su
       (cu/grepkill! :stop :fuzz_dist)
       (cu/grepkill! :stop :antidote)))

    (resume! [this test node]
      (c/su
       (cu/grepkill! :cont :antidote)
       (cu/grepkill! :cont :fuzz_dist)))))
