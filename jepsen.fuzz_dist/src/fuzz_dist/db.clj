(ns fuzz-dist.db
  (:require [aleph.http :as http]
            [clojure.string :as str]
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
    (setup! [_ test node]
      ;; cp from control to node to install
      (scp/scp! {:port 22 :private-key-path (str util/control-root "/" ".ssh/id_rsa")}
                [(str util/control-antidote "/" "_build/default/rel/antidote")]
                (str "root" "@" node ":" "/root"))

      (c/cd util/node-antidote
            (c/exec
             (c/env {:NODE_NAME (util/n-to-fqdn node "antidote")
                     :COOKIE "antidote"})
             "bin/antidote"
             "daemon"))

      ;; cp from control to node to install
      (scp/scp! {:port 22 :private-key-path (str util/control-root "/" ".ssh/id_rsa")}
                [(str util/control-fuzz-dist "/" "_build/prod/rel/fuzz_dist")]
                (str "root" "@" node ":" "/root"))

      (c/cd util/node-fuzz-dist
            (c/exec
             (c/env {:NODE_NAME (util/n-to-fqdn node "fuzz_dist")
                     :COOKIE "fuzz_dist"})
             "bin/fuzz_dist"
             "daemon"))

      (Thread/sleep 15000))

    (teardown! [_ test node]
      (try
        (c/cd util/node-antidote
              (c/exec
               (c/env {:NODE_NAME (util/n-to-fqdn node "antidote")
                       :COOKIE "antidote"})
               "bin/antidote"
               "stop"))
        (c/cd util/node-fuzz-dist
              (c/exec
               (c/env {:NODE_NAME (util/n-to-fqdn node "fuzz_dist")
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
      (let [conn @(http/websocket-client (fd-client/node-url node))]
      ;; TODO test type: :ok response
        (info ":db :setup_primary return:"
              (fd-client/ws-invoke conn
                                   :db
                                   :setup_primary (util/nodes-to-fqdn (:nodes test) "antidote")))
        (s/close! conn)))

    db/LogFiles
    (log-files [db test node]
      {"/root/fuzz_dist/tmp/log"    "fuzz_dist_log"
       "/root/antidote/logger_logs" "antidote_logger_logs"
       "/root/antidote/log"         "antidote_log"})))

;; TODO node stopper/starter
(defn start!
  [test node]
  :not-implemented)

;; TODO node stopper/starter
(defn stop!
  [test node]
  :not-implemented)