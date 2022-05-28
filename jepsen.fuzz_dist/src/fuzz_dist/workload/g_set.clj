(ns fuzz-dist.workload.g-set
  (:require [clojure.tools.logging :refer :all]
            [fuzz-dist.client :as fd-client]
            [jepsen
             [checker :as checker]
             [client :as client]
             [generator :as gen]]
            [manifold.stream :as s]))

(defn g-set-adds [prefix]
  (map (fn [x] {:type :invoke, :f :add, :value (str prefix x)}) (drop 1 (range))))

(defn g-set-reads [final?]
  (if final?
    (repeat {:type :invoke, :f :read, :final? true, :value nil})
    (repeat {:type :invoke, :f :read, :value nil})))

(defrecord GSetClient [conn]
  client/Client
  (open! [this test node]
    (info "GSetClient/open" (fd-client/node-url node))
    (assoc this :conn (fd-client/get-ws-conn fd-client/node-url node)))

  (setup! [this test])

  (invoke! [_ test op]
    (case (:f op)
      :add  (let [resp (fd-client/ws-invoke conn :g_set :add op)]
              (case (:type resp)
                "ok"   (assoc op :type :ok)
                "fail" (assoc op :type :fail, :error (:error resp))
                "info" (assoc op :type :info, :error (:error resp))
                (assoc op :type :info, :error (str resp))))
      :read (let [resp (fd-client/ws-invoke conn :g_set :read op)]
              (case (:type resp)
                ;; sort returned set for human readability, json unorders
                "ok"   (assoc op :type :ok,   :value (sort (:value resp)))
                "fail" (assoc op :type :fail, :error (:error resp))
                "info" (assoc op :type :info, :error (:error resp))
                (assoc op :type :info, :error (str resp))))))

  (teardown! [this test])

  (close! [_ test]
    (s/close! conn)))

(defn workload
  "Constructs a workload, {:client, :preamble-generator, :generator, :final-generator, :checker},
   for a g-set, given options from the CLI test constructor."
  [opts]
  {:client    (GSetClient. nil)
   :preamble-generator (->> (g-set-adds "pre-")
                            (gen/stagger (/ (count (:nodes opts))))
                            (gen/time-limit 5)
                            (gen/clients))
   :generator (if (not (:linearizable? opts))
                (gen/mix [(g-set-adds "")
                          (g-set-reads false)])
                (g-set-adds ""))
   :final-generator (gen/phases
                     ;; a simple sequence of transactions to help clarify end state and final reads
                     (gen/log "Final adds in healed state...")
                     (->>
                      (g-set-adds "final-")
                      (gen/stagger (/ 1))
                      (gen/time-limit 10)
                      (gen/clients))

                     (gen/log "Let database quiesce...")
                     (gen/sleep 10)

                     (gen/log "Final read...")
                     (->>
                      (g-set-reads true)
                      (gen/once)
                      (gen/each-thread)
                      (gen/clients)))
   :checker (checker/set-full
             {:linearizable? (:linearizable? opts)})})
