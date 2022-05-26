(ns fuzz-dist.core
  (:require
   [clojure.string :as str]
   [fuzz-dist.db :as db]
   [fuzz-dist.workload [g-set :as g-set]]
   [jepsen
    [cli :as cli]
    [checker :as checker]
    [generator :as gen]
    [tests :as tests]]
   [jepsen.nemesis.combined :as nc]
   [jepsen.checker.timeline :as timeline]
   [jepsen.os.debian :as debian]))

(def workloads
  "A map of workload names to functions that construct workloads, given opts."
  {:g-set g-set/workload})

(def nemeses
  "The types of faults our nemesis can produce"
  #{:partition :pause :kill})

(def special-nemeses
  "A map of special nemesis names to collections of faults"
  {:none      []
   :standard  [:partition]
   :process   [:pause :kill]
   :all       [:partition :pause :kill]})

(def test-all-nemeses
  "A collection of partial options maps for various nemeses we want to run as a
  part of test-all."
  [{:nemesis nil}
   {:nemesis #{:partition}}
   {:nemesis #{:pause}}
   {:nemesis #{:kill}}])

(def partition-targets
  "Valid targets for partition nemesis operations."
  #{:majority :minority-third :one :majorities-ring :primaries})

(def db-targets
  "Valid targets for DB nemesis operations."
  #{:one :primaries :minority-third :majority :all})

(defn combine-workload-package-generators
  "Constructs a test generator by combining workload and package generators
   configured with CLI test opts"
  [opts workload package]

  (gen/phases
   (gen/log "Peaceful preamble, no nemesis, minimal activity")
   (:preamble-generator workload)

   (gen/log "Workload with nemesis")
   (->> (:generator workload)
        (gen/stagger (/ (:rate opts)))
        (gen/nemesis (:generator package))
        (gen/time-limit (:time-limit opts)))

   (gen/log "Final nemesis")
   (gen/nemesis (:final-generator package))

   (gen/log "Final workload")
   (:final-generator workload)))

(defn fuzz-dist-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
                      :concurrency, ...), constructs a test map."
  [opts]
  (let [db            (db/db :git)
        workload-name (:workload opts)
        workload      ((workloads workload-name) opts)
        package       (nc/nemesis-package
                       {:db        db
                        :nodes     (:nodes opts)
                        :faults    (:nemesis opts)
                        :partition {:targets (:partition-targets opts)}
                        :pause     {:targets (:db-targets opts)}
                        :kill      {:targets (:db-targets opts)}
                        :interval  (:nemesis-interval opts)})]

    (merge tests/noop-test
           opts
           {:name       (str "fuzz-dist"
                             "-Antidote"
                             "-" (count (:nodes opts)) "xdc"
                             "-" (if (empty? (:nemesis opts))
                                   (str ":no-faults")
                                   (str (seq (:nemesis opts))  "-" (:nemesis-interval opts) "s"))
                             "-for-" (:time-limit opts) "s"
                             "-" (:rate opts) "ts"
                             (if (:linearizable? opts)
                               (str "-linearizable?=true")
                               (str ""))
                             "-" workload-name)
            :os         debian/os
            :db         db
            :client     (:client workload)
            :nemesis    (:nemesis package)
            :generator  (combine-workload-package-generators opts workload package)
            :checker    (checker/compose
                         {:workload   (:checker workload)
                          :perf       (checker/perf
                                       {:nemeses (:perf package)})
                          :timeline   (timeline/html)
                          :stats      (checker/stats)
                          :exceptions (checker/unhandled-exceptions)
                          ;; TODO log file patterns
                          ;; :logs       (checker/log-file-pattern #"ERROR" ".log")
                          })
            :logging    {:overrides
                         ;; TODO: turn off SLF4J logging
                         {"io.netty.util.internal.InternalThreadLocalMap" :off
                          "io.netty.util.internal.logging.InternalLoggerFactory" :off}}})))

(def validate-non-neg
  [#(and (number? %) (not (neg? %))) "Must be non-negative"])

(defn parse-comma-kws
  "Takes a comma-separated string and returns a collection of keywords."
  [spec]
  (->> (str/split spec #",")
       (remove #{""})
       (map keyword)))

(defn parse-nemesis-spec
  "Takes a comma-separated nemesis string and returns a collection of keyword
  faults."
  [spec]
  (->> (parse-comma-kws spec)
       (mapcat #(get special-nemeses % [%]))
       set))

(def test-opt-spec
  "Options for tests."
  [[nil "--db-dir DIRECTORY" "Directory with database release"
    :default "~/projects/antidote/_build/default/rel/antidote"
    ;; :parse-fn read-string
    ]

   [nil "--fuzz-dist-dir DIRECTORY" "Directory with fuzz_dist release"
    :default "~/projects/fuzz_dist/beam.fuzz_dist/_build/prod/rel/fuzz_dist"
    ;; :parse-fn read-string
    ]
   ["-w" "--workload NAME" "What workload to run."
    :default :g-set
    :parse-fn keyword
    :validate [workloads (cli/one-of workloads)]]])

(def opt-spec
  "Additional command line options."
  [[nil "--linearizable? BOOLEAN" "Check for linearizability"
    :default false
    :parse-fn parse-boolean]

   [nil "--nemesis FAULTS" "A comma-separated list of nemesis faults to enable"
    :default (:standard special-nemeses)
    :parse-fn parse-nemesis-spec
    :validate [(partial every? (into nemeses (keys special-nemeses)))
               (str (cli/one-of nemeses) ", or " (cli/one-of special-nemeses))]]

   [nil "--nemesis-interval SECONDS" "How long to wait between nemesis faults."
    :default  15
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "must be a positive number"]]

   [nil "--partition-targets TARGETS" "A comma-separated list of nodes to target for network partitions; e.g. one,all"
    :default (vec partition-targets)
    :parse-fn parse-comma-kws
    :validate [(partial every? partition-targets) (cli/one-of partition-targets)]]

   [nil "--db-targets TARGETS" "A comma-separated list of nodes to target for db nemesus; e.g. one,all"
    :default (vec db-targets)
    :parse-fn parse-comma-kws
    :validate [(partial every? db-targets) (cli/one-of db-targets)]]

   [nil "--rate HZ" "Target number of ops/sec"
    :default  10
    :parse-fn read-string
    :validate validate-non-neg]])

(defn all-tests
  "Takes parsed CLI options and constructs a sequence of tests
  using all standard nemeses."
  [opts]
  (let [workloads (if-let [w (:workload opts)]
                    [w]
                    (keys workloads))
        nemeses   test-all-nemeses
        counts    (range (:test-count opts))]
    (for [w workloads, n nemeses, l [false true], i counts]
      (-> opts
          (assoc :workload w, :linearizable? l)
          (merge n)
          fuzz-dist-test))))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
                browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  fuzz-dist-test
                                         :opt-spec (concat test-opt-spec opt-spec)})
                   (cli/test-all-cmd    {:tests-fn all-tests
                                         :opt-spec (concat test-opt-spec opt-spec)})
                   (cli/serve-cmd))
            args))
