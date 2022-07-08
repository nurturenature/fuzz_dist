(ns fuzz-dist.workload.pn-counter
  "An eventually-consistent counter which supports increments and decrements.
  Validates that the final read on each node has a value which is the sum of
  all known (or possible) increments and decrements."
  (:require [clojure.tools.logging :refer [info]]
            [fuzz-dist.client :as fd-client]
            [fuzz-dist.tests.pn-counter :as pn-counter]
            [jepsen
             [client :as client]
             [independent :as independent]]))

(defrecord PNCounterClient [conn]
  client/Client
  (open! [this _test node]
    (info "PNCounterClient/open" (fd-client/node-url node))
    (assoc this :conn (fd-client/get-ws-conn fd-client/node-url node)))

  (setup! [_this _test])

  (invoke! [_ _test op]
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
      :read (let [{:keys [type value error] :as resp} (fd-client/ws-invoke conn :pn_counter :read op)
                  [k v] value]
              (case type
                "ok"   (assoc op :type :ok,   :value (independent/tuple k (long v)))
                "fail" (assoc op :type :fail, :error error)
                "info" (assoc op :type :info, :error error)
                (assoc op :type :info, :error (str resp))))))

  (teardown! [_this _test])

  (close! [_this _test]
    (fd-client/ws-close conn)))

(defn workload
  "Constructs a workload:
  ```clojure
  {:client, :generator, :final-generator, :checker}
  ```
  for a pn-counter, given options from the CLI test constructor."
  [opts]
  (merge {:client (PNCounterClient. nil)}
         (pn-counter/test opts)))
