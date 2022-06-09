(ns fuzz-dist.client
  (:require [aleph.http :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :refer :all]
            [manifold.stream :as s])
  (:use [slingshot.slingshot :only [throw+]]))

(defn node-url
  "An HTTP url for connecting to a node's FuzzDist Elixir client."
  [node]
  (str "ws://" node ":8080" "/fuzz_dist/jep_ws"))

(defn get-ws-conn
  "Returns a websocket connection as a bidirectional stream.
  Exceptions are intentional to invalidate clients"
  ([url node] (get-ws-conn url node 1000))
  ([url node timeout]
   (let [conn (deref (http/websocket-client (url node)) timeout nil)]
     (if (not (nil? conn))
       conn
       (throw+ [:type :info :error "Websocket client open failed, timeout?"])))))

(defn ws-invoke
  "Invokes the op over the ws connection.
  On the BEAM side, a :cowboy_websocket_handler dispatches to an Elixir @behavior.
  Be conservative, throw Exceptions, let this client crash
  to invalidate current op and get fresh client."
  ([conn mod fun op] (ws-invoke conn mod fun op 1000))
  ([conn mod fun op timeout]
   (if (not @(s/try-put! conn
                         (json/generate-string
                          {:mod mod
                           :fun fun
                           :args op})
                         timeout))
     (throw+ [:type :info
              :error (str "Websocket put failed: " {:conn (->> conn
                                                               s/description
                                                               :sink
                                                               :connection
                                                               :remote-address)
                                                    :mod mod
                                                    :fun fun
                                                    :args op})]))

   (let [resp @(s/try-take! conn :fail timeout :timeout)]
     (case resp
       (:fail, :timeout) (throw+ [:type :info
                                  :error (str "Websocket take " resp " after putting: "
                                              {:conn (->> conn
                                                          s/description
                                                          :source
                                                          :connection
                                                          :remote-address)
                                               :mod mod
                                               :fun fun
                                               :args op})])
       (json/parse-string resp true)))))