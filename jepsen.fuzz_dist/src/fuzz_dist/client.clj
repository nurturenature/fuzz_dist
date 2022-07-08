(ns fuzz-dist.client
  (:require [aleph.http :as http]
            [cheshire.core :as json]
            [manifold.stream :as s]
            [slingshot.slingshot :refer [throw+]]))

(defn node-url
  "An HTTP url for connecting to a node's FuzzDist Elixir client."
  [node]
  (str "ws://" node ":8080" "/fuzz_dist/jep_ws"))

(defn get-ws-conn
  "Returns a websocket connection as a bidirectional stream.
  Exceptions are intentional to invalidate clients"
  ([url node] (get-ws-conn url node 1000))
  ([url node timeout]
   (let [conn {:ws   (deref (http/websocket-client (url node)) timeout nil)
               :node node
               :url  url}]
     (if (not (nil? (:ws conn)))
       conn
       (throw+ [:type :info :error "Websocket client open failed, timeout?"])))))

(defn ws-invoke
  "Invokes the op over the ws connection.
  On the BEAM side, a :cowboy_websocket_handler dispatches to an Elixir @behavior.
  Be conservative, throw Exceptions, let this client crash
  to invalidate current op and get fresh client."
  ([conn mod fun op] (ws-invoke conn mod fun op 1000))
  ([{:keys [ws node url] :as _conn} mod fun op timeout]
   (when (not @(s/try-put! ws
                           (json/generate-string
                            {:mod mod
                             :fun fun
                             :args op})
                           timeout))
     (throw+ [:type :info
              :error (str "Websocket put failed: " {:conn [node url]
                                                    :mod mod
                                                    :fun fun
                                                    :args op})]))

   (let [resp @(s/try-take! ws :fail timeout :timeout)]
     (case resp
       (:fail, :timeout) (throw+ [:type :info
                                  :error (str "Websocket take " resp " after putting: "
                                              {:conn [node url]
                                               :mod mod
                                               :fun fun
                                               :args op})])
       (assoc (json/parse-string resp true)
              :node node)))))

(defn ws-close
  "Closes the websocket connection."
  [{:keys [ws] :as _conn}]
  (s/close! ws))