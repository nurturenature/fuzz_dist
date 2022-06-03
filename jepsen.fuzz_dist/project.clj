(defproject fuzz-dist "0.0.1-scrappy"
  :description "Jepsen framework for fuzz_dist."
  :url "https://github.com/nurturenature"
  :license {:name "Licensed under the Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[com.google.guava/guava "30.1-jre"]
                 [org.clojure/clojure "1.11.1"]
                 [aleph "0.4.6"]
                 [cheshire "5.10.2"]
                 [jepsen "0.2.7-SNAPSHOT"]
                 [manifold "0.2.3"]]
  :main fuzz-dist.core
  :repl-options {:init-ns fuzz-dist.core}
  :plugins [[jonase/eastwood "1.2.3"]])
