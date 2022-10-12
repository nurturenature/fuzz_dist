(ns fuzz-dist.core
  (:require
   [clojure.string :as str]
   [fuzz-dist.db :as db]
   [fuzz-dist.workload
    [g-set :as g-set]
    [pn-counter :as pn-counter]]
   [jepsen
    [cli :as cli]
    [checker :as checker]
    [generator :as gen]
    [net :as net]
    [tests :as tests]]
   [jepsen.nemesis.combined :as nc]
   [jepsen.checker.timeline :as timeline]
   [jepsen.os.debian :as debian]))

(def workloads
  "A map of workload names to functions that construct workloads, given opts."
  {:g-set      g-set/workload
   :pn-counter pn-counter/workload})

(def nemeses
  "The types of faults our nemesis can produce"
  #{:partition :pause :kill :packet})

(def special-nemeses
  "A map of special nemesis names to collections of faults"
  {:none      []
   :standard  [:partition :packet]
   :process   [:pause :kill]
   :all       [:partition :packet :pause :kill :file-corruption]})

(def test-all-nemeses
  "A collection of partial options maps for various nemeses we want to run as a
  part of test-all."
  [{:nemesis nil}
   {:nemesis #{:partition}}
   {:nemesis #{:packet}}
   {:nemesis #{:pause}}
   {:nemesis #{:partition :packet :pause}}
   {:nemesis #{:kill} :antidote-sync-log? true}
   {:nemesis #{:file-corruption}}
   {:nemesis #{:file-corruption :kill} :antidote-sync-log? true}])

(def partition-targets
  "Valid targets for partition nemesis operations."
  #{:majority :minority-third :one :majorities-ring :primaries})

(def db-targets
  "Valid targets for DB nemesis operations."
  #{:one :primaries :minority-third :minority :majority :all})

(def topologies
  "Topologies that can be tested.
  :dcs   topology {dc {n}, dc {n}, ...} to test inter-dc protocol
  :nodes topology {dc {n, n, ...}}      to test intra-dc protocol"
  #{:dcs :nodes})

(defn combine-workload-package-generators
  "Constructs a test generator by combining workload and package generators
   configured with CLI test opts"
  [opts workload package]

  (gen/phases
   (gen/log "Workload with nemesis")
   (->> (:generator workload)
        (gen/stagger (/ (:rate opts)))
        (gen/nemesis (:generator package))
        (gen/time-limit (:time-limit opts)))

   (gen/log "Final nemesis")
   (gen/nemesis (:final-generator package))

   (gen/log "Final workload")
   (:final-generator workload)))

(defn test-name
  "Meaningful test name."
  [opts]
  (str "Antidote"
       " " (:workload opts)
       " " (case (:topology opts)
             :dcs   (str (count (:nodes opts)) "xdcn1")
             :nodes (str "1" "xdcn" (count (:nodes opts))))
       " " (if (empty? (:nemesis opts))
             (str ":no-faults")
             (str (seq (:nemesis opts))))
       (if (:antidote-sync-log? opts)
         (str " sync-log")
         (str ""))
       (if (:linearizable? opts)
         (str " linearizable")
         (str ""))))

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
                        :packet    {:targets (:db-targets opts)
                                    :behaviors (for [_ (range 10)]
                                                 (->> net/all-packet-behaviors
                                                      (random-sample (/ (+ 1 (rand-int 3))
                                                                        (count net/all-packet-behaviors)))
                                                      (into {})))
                                    ;          [; typical day
                                    ;           {:delay {}}
                                    ;           ; ummm, about that firmware update...
                                    ;           {:corrupt {:percent :33%}}
                                    ;           ; failing adapter?!?
                                    ;           {:delay {},
                                    ;            :corrupt   {:percent :15%},
                                    ;            :duplicate {:percent :25% :correlation :75%}
                                    ;            :reorder   {:percent :30% :correlation :80%}}]
                                    }
                        :pause     {:targets (:db-targets opts)}
                        :kill      {:targets [;; (:db-targets opts)
                                              ;; ["n1"]
                                              :minority]}
                        :file-corruption {:targets     [:minority
                                                        ;; ["n1"]
                                                        ;; ["n1" "n2"]
                                                        ;; ["n1" "n2" "n3"]
                                                        ]
                                          :corruptions [{:type :bitflip :file db/node-antidote-data-dir
                                                         :probability {:distribution :one-of :values [1e-2 1e-3 1e-4]}}
                                                        {:type :bitflip :file db/node-antidote-riak-dir
                                                         :probability {:distribution :one-of :values [1e-2 1e-3 1e-4]}}
                                                        {:type :truncate :file db/node-antidote-data-dir
                                                         :drop {:distribution :geometric :p 1e-3}}
                                                        {:type :truncate :file db/node-antidote-riak-dir
                                                         :drop {:distribution :geometric :p 1e-3}}]}
                        :interval  (:nemesis-interval opts)})]

    (merge tests/noop-test
           opts
           {:name       (test-name opts)
            :os         debian/os
            :db         db
            :client     (:client workload)
            :nemesis    (:nemesis package)
            :generator  (combine-workload-package-generators opts workload package)
            :checker    (checker/compose
                         {:workload   ((:checker workload)
                                       {:nemeses (:perf package)})
                          :perf       (checker/perf
                                       {:nemeses (:perf package)})
                          :timeline   (timeline/html)
                          :stats      (checker/stats)
                          :exceptions (checker/unhandled-exceptions)
                          ; TODO confirm: do error messages in Antidote count as an error?
                          ; :logs-antidote  (checker/log-file-pattern #"error\:"   db/antidote-log-file)
                          ; disterl :nodeup/down msgs are not considered errors
                          ; too many false positives
                          ; :logs-fuzz-dist (checker/log-file-pattern #"\[error\]\ (?!\*\* Node :fuzz_dist\@.+\ not\ responding\ \*\*$)" db/fuzz-dist-log-file)
                          })
            :logging    {:overrides
                         ;; TODO: how to turn off SLF4J logging?
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

(def test-cli-opts
  "CLI options just for test"
  [[nil "--nemesis FAULTS" "A comma-separated list of nemesis faults to enable"
    :default  nil
    :parse-fn parse-nemesis-spec
    :validate [(partial every? (into nemeses (keys special-nemeses)))
               (str (cli/one-of nemeses) ", or " (cli/one-of special-nemeses))]]

   [nil "--topology TOPOLOGY" "Topology of cluster, multiple dcs or single dc with multiple nodes"
    :default  :nodes
    :parse-fn keyword
    :validate [topologies (cli/one-of topologies)]]

   ["-w" "--workload NAME" "What workload to run."
    :default :g-set
    :parse-fn keyword
    :validate [workloads (cli/one-of workloads)]]])

(def test-all-cli-opts
  "CLI options just for test-all.
   Lack of :default value, e.g. will be nill, causes test-all to use all values"
  [[nil "--nemesis FAULTS" "A comma-separated list of nemesis faults to enable"
    :parse-fn parse-nemesis-spec
    :validate [(partial every? (into nemeses (keys special-nemeses)))
               (str (cli/one-of nemeses) ", or " (cli/one-of special-nemeses))]]

   [nil "--topology TOPOLOGY" "Topology of cluster, multiple dcs or single dc with multiple nodes"
    :parse-fn keyword
    :validate [topologies (cli/one-of topologies)]]

   ["-w" "--workload NAME" "What workload to run."
    :parse-fn keyword
    :validate [workloads (cli/one-of workloads)]]])

(def cli-opts
  "Additional command line options."
  [[nil "--antidote-sync-log? BOOLEAN" "Passed to Antidote cmd line as -antidote sync_log boolean"
    :default false
    :parse-fn boolean]

   [nil "--counter-strategy STRATEGIES" "E.g. grow,swing,rand"
    :default #{:grow :swing :rand}
    :parse-fn (fn [spec] (->> spec parse-comma-kws set))]

   [nil "--db-dir DIRECTORY" "Directory with database release"
    :default "/jepsen/antidote"]

   [nil "--db-targets TARGETS" "A comma-separated list of nodes to target for db nemesus; e.g. one,all"
    :default (vec db-targets)
    :parse-fn parse-comma-kws
    :validate [(partial every? db-targets) (cli/one-of db-targets)]]

   ;; "recon_trace:calls({cure, update_objects, fun(_) -> return_trace() end}, 10000)."
   [nil "--erlang-eval EXPRESSION" "Expression to be evaluated on each node after starting db."
    :default nil]

   [nil "--fuzz-dist-dir DIRECTORY" "Directory with fuzz_dist release"
    :default "/jepsen/fuzz_dist"]

   [nil "--kernel-logger-level LEVEL" "Passed to Antidote cmd line as -kernel logger_level level"
    :default :debug
    :parse-fn keyword]

   [nil "--linearizable? BOOLEAN" "Check for linearizability"
    :default false
    :parse-fn boolean]

   [nil "--nemesis-interval SECONDS" "How long to wait between nemesis faults."
    :default  15
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "must be a positive number"]]

   [nil "--partition-targets TARGETS" "A comma-separated list of nodes to target for network partitions; e.g. one,all"
    :default (vec partition-targets)
    :parse-fn parse-comma-kws
    :validate [(partial every? partition-targets) (cli/one-of partition-targets)]]

   [nil "--rate HZ" "Target number of ops/sec"
    :default  10
    :parse-fn read-string
    :validate validate-non-neg]])

(defn all-tests
  "Takes parsed CLI options and constructs a sequence of tests:
     :topology or :workload or :nemesis
   = nil will iterate through all values
   running each configuration :test-count times."
  [opts]
  (let [workloads (if-let [w (:workload opts)]
                    [w]
                    (keys workloads))
        topos     (if-let [t (:topology opts)]
                    [t]
                    topologies)
        nemeses   (if-let [n (:nemesis opts)]
                    [{:nemesis n}]
                    test-all-nemeses)
        counts    (range (:test-count opts))]
    (for [w workloads, t topos, n nemeses, _i counts]
      (-> opts
          (assoc :workload w
                 :topology t)
          (merge n)
          fuzz-dist-test))))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
                browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  fuzz-dist-test
                                         :opt-spec (into cli-opts
                                                         test-cli-opts)})
                   (cli/test-all-cmd    {:tests-fn all-tests
                                         :opt-spec (into cli-opts
                                                         test-all-cli-opts)})
                   (cli/serve-cmd))
            args))
