(ns fuzz-dist.workload.pn-counter
  "An eventually-consistent counter which supports increments and decrements.
  Validates that the final read on each node has a value which is the sum of
  all known (or possible) increments and decrements."
  (:require [clojure.tools.logging :refer :all]
            [fuzz-dist.client :as fd-client]
            [fuzz-dist.checker.pn-counter :as pn-counter]
            [jepsen
             [client :as client]
             [generator :as gen]]
            [manifold.stream :as s]
            [slingshot.slingshot :refer [try+ throw+]]))

(defn pn-counter-adds []
  (fn [] {:type :invoke, :f :add, :value (- 1000 (rand-int 2001))}))

(defn pn-counter-reads [final?]
  (if final?
    (repeat {:type :invoke, :f :read, :final? true, :value nil})
    (repeat {:type :invoke, :f :read, :value nil})))

(defrecord PNCounterClient [conn]
  client/Client
  (open! [this test node]
    (info "PNCounterClient/open" (fd-client/node-url node))
    (assoc this :conn (fd-client/get-ws-conn fd-client/node-url node)))

  (setup! [this test])

  (invoke! [_ test op]
    (case (:f op)
      :add  (let [resp (fd-client/ws-invoke conn :pn_counter :add op)]
              (case (:type resp)
                "ok"   (assoc op :type :ok)
                "fail" (assoc op :type :fail, :error (:error resp))
                "info" (assoc op :type :info, :error (:error resp))
                (assoc op :type :info, :error (str resp))))
      :read (let [resp (fd-client/ws-invoke conn :pn_counter :read op)]
              (case (:type resp)
                "ok"   (assoc op :type :ok,   :value (long (:value resp)))
                "fail" (assoc op :type :fail, :error (:error resp))
                "info" (assoc op :type :info, :error (:error resp))
                (assoc op :type :info, :error (str resp))))))

  (teardown! [this test])

  (close! [_ test]
    (s/close! conn)))

(defn workload
  "Constructs a workload, {:client, :generator, :final-generator, :checker},
   for a pn-counter, given options from the CLI test constructor."
  [opts]
  {:client (PNCounterClient. nil)
   :generator (gen/mix [(pn-counter-adds)
                        (pn-counter-reads false)])
   :final-generator (gen/phases
                     (gen/log "Let database quiesce...")
                     (gen/sleep 10)

                     (gen/log "Final read...")
                     (->>
                      (pn-counter-reads true)
                      (gen/once)
                      (gen/each-thread)
                      (gen/clients)))
   :checker (pn-counter/checker)})
