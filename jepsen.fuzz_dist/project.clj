(defproject fuzz-dist "0.0.1-scrappy"
  :description "Jepsen framework for fuzz_dist."
  :url "https://github.com/nurturenature"
  :license {:name "Licensed under the Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :main fuzz-dist.core
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [aleph "0.4.6"]
                 [cheshire "5.10.2"]
                 [jepsen "0.2.6"]
                 [manifold "0.2.3"]]
  :repl-options {:init-ns fuzz-dist.core})

