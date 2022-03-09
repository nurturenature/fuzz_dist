(defproject jepsen.fuzz_dist "0.0.1-SNAPSHOT"
  :description "Jepsen framework for FuzzDist"
  :url "https://github.com/nurturenature"
  :license {:name "This work is licensed under CC BY-SA 4.0"
            :url "http://creativecommons.org/licenses/by-sa/4.0/"}
  :main jepsen.fuzz_dist
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [aleph "0.4.6"]
                 [cheshire "5.10.2"]
                 [jepsen "0.2.6"]
                 [manifold "0.2.3"]]
  :repl-options {:init-ns jepsen.fuzz_dist})

