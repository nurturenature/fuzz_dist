(ns fuzz-dist.workload.pn-counter
  "An eventually-consistent counter which supports increments and decrements.
  Validates that the final read on each node has a value which is the sum of
  all known (or possible) increments and decrements."
  (:require [clojure.tools.logging :refer :all]
            [fuzz-dist.client :as fd-client]
            [fuzz-dist.tests.pn-counter :as pn-counter]
            [jepsen.client :as client]
            [manifold.stream :as s]
            [slingshot.slingshot :refer [try+ throw+]]))

(defrecord PNCounterClient [conn]
  client/Client
  (open! [this test node]
    (info "PNCounterClient/open" (fd-client/node-url node))
    (assoc this :conn (fd-client/get-ws-conn fd-client/node-url node)))

  (setup! [this test])

  (invoke! [_ test op]
    (case (:f op)
      :increment (let [resp (fd-client/ws-invoke conn :pn_counter :increment op)]
                   (case (:type resp)
                     "ok"   (assoc op :type :ok)
                     "fail" (assoc op :type :fail, :error (:error resp))
                     "info" (assoc op :type :info, :error (:error resp))
                     (assoc op :type :info, :error (str resp))))
      :decrement (let [resp (fd-client/ws-invoke conn :pn_counter :decrement op)]
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
  "Constructs a workload:
  ```clojure
  {:client, :generator, :final-generator, :checker}
  ```
  for a pn-counter, given options from the CLI test constructor."
  [opts]
  (merge {:client (PNCounterClient. nil)}
         (pn-counter/test opts)))
