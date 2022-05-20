(ns fuzz-dist.client
  (:require [cheshire.core :as json]
            [clojure.tools.logging :refer :all]
            [manifold.stream :as s])
  (:use [slingshot.slingshot :only [try+]]))

(defn node-url
  "An HTTP url for connecting to a node's FuzzDist Elixir client."
  [node]
  (str "ws://" node ":8080" "/fuzz_dist/jep_ws"))

(defn ws-invoke
  "Invokes the op over the ws connection.
  On the BEAM side a :cowboy_websocket_handler dispatches to an Elixir @behavior."
  [conn mod fun op]
  (try+
   (s/try-put! conn
               (json/generate-string
                {:mod mod
                 :fun fun
                 :args op})
               5000)
    ;; TODO: catch timeouts
   (json/parse-string @(s/try-take! conn 60000) true)
    ;; TODO: catch timeouts
   (catch Object e
     ;; TODO: smarter Exception -> info/fail, e.g. Connection refused is a fail
     (error (str "ws-invoke Exception:" e))
     {:type "info" :error (.getCause e)})))
