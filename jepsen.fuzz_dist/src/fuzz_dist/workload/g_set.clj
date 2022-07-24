(ns fuzz-dist.workload.g-set
  (:require [clojure.tools.logging :refer [info]]
            [fuzz-dist.client :as fd-client]
            [fuzz-dist.checker.final-reads :as final-reads]
            [jepsen
             [checker :as checker]
             [client :as client]
             [generator :as gen]]))

(defn g-set-adds []
  (map (fn [x] {:type :invoke, :f :add, :value x}) (drop 1 (range))))

(defn g-set-reads [final?]
  (if final?
    (repeat {:type :invoke, :f :read, :final? true, :value nil})
    (repeat {:type :invoke, :f :read, :value nil})))

(defrecord GSetClient [conn]
  client/Client
  (open! [this _test node]
    (info "GSetClient/open" (fd-client/node-url node))
    (assoc this :conn (fd-client/get-ws-conn fd-client/node-url node)))

  (setup! [_this _test])

  (invoke! [_this _test op]
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

  (teardown! [_this _test])

  (close! [_this _test]
    (fd-client/ws-close conn)))

(defn workload
  "Constructs a workload, {:client, :generator, :final-generator, :checker},
   for a g-set, given options from the CLI test constructor."
  [opts]
  {:client    (GSetClient. nil)
   :generator (if (not (:linearizable? opts))
                (gen/mix [(g-set-adds)
                          (g-set-reads false)])
                (g-set-adds))
   :final-generator (gen/phases
                     (gen/log "Let database quiesce...")
                     (gen/sleep 10)

                     (gen/log "Final read...")
                     (->>
                      (g-set-reads true)
                      (gen/once)
                      (gen/each-thread)
                      (gen/clients)))
   :checker (fn g-set-checker ; support passing opts in test map, e.g. perf map for nemeses
              ([] (g-set-checker {}))
              ([opts']
               (let [opts' (merge opts opts')]
                 (checker/compose
                  {:final-reads (final-reads/checker)
                   :set-full    (checker/set-full opts')}))))})
