(ns fuzz-dist.workload.g-set
  (:require [aleph.http :as http]
            [clojure.tools.logging :refer :all]
            [fuzz-dist.client :as fd-client]
            [jepsen
             [checker :as checker]
             [client :as client]
             [generator :as gen]]
            [manifold.stream :as s])
  (:use [slingshot.slingshot :only [try+]]))

(defrecord GSetClient [conn]
  client/Client
  (open! [this test node]
    (info node "Client/open" (fd-client/node-url node))
    (try+
     (assoc this :conn @(http/websocket-client (fd-client/node-url node)))
     (catch Exception e
       :g-set-client-open-exception)))
  ;; TODO more specific Exception handling

  (setup! [this test])

  (invoke! [_ test op]
    (case (:f op)
      :add  (let [resp (fd-client/ws-invoke conn :g_set :add op)]
              (case (:type resp)
                "ok"   (assoc op :type :ok)
                "fail" (assoc op :type :fail, :error (:error resp))
                ;; TODO: explicit "info"
                (assoc op :type :info, :error (str resp))))
      :read (let [resp (fd-client/ws-invoke conn :g_set :read op)]
              (case (:type resp)
                ;; sort returned set for human readability, json unorders
                "ok"   (assoc op :type :ok,   :value (sort (:value resp)))
                "fail" (assoc op :type :fail, :error (:error resp))
                ;; TODO: explicit "info"
                (assoc op :type :info, :error (str resp))))))

  (teardown! [this test])

  (close! [_ test]
    (s/close! conn)))

(defn workload
  "Constructs a workload, {:client, :generator, :final-generator, :checker},
   for a g-set, given options from the CLI test constructor."
  [opts]
  {:client    (GSetClient. nil)
   :generator (gen/mix [(map (fn [x] {:type :invoke, :f :add, :value (str x)}) (drop 1 (range)))
                        (repeat {:type :invoke, :f :read, :value nil})])
   :final-generator (gen/phases
                     ;; a simple sequence of transactions to help clarify end state and final reads
                     (gen/log "Final adds/reads in healed state...")
                     (gen/sleep 1)
                     (gen/clients (gen/each-thread {:type :invoke :f :read :value nil}))
                     (gen/sleep 1)
                     (gen/clients
                      (->>
                       (gen/mix [(map (fn [x] {:type :invoke, :f :add, :value (str "final-" x)}) (drop 1 (range)))
                                 (repeat {:type :invoke, :f :read, :value nil})])
                       (gen/stagger (/ 2))
                       (gen/time-limit 10)))
                     (gen/sleep 1)

                     (gen/log "Let database quiesce...")
                     (gen/sleep 10)

                     (gen/log "Final read...")
                     (gen/sleep 1)
                     (gen/clients (gen/each-thread {:type :invoke :f :read :value nil})))
   :checker (checker/set-full)})
