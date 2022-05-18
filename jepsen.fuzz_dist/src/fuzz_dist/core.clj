(ns fuzz-dist.core
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :refer :all]
   [fuzz-dist
    [db :as db]
    [nemesis :as nemesis]
    [util :as util]]
   [fuzz-dist.workload [g-set :as g-set]]
   [jepsen
    [cli :as cli]
    [checker :as checker]
    [generator :as gen]
    [tests :as tests]]
   [jepsen.checker.timeline :as timeline]
   [jepsen.os.debian :as debian]))

(defn g-set-read [_ _]
  (repeat {:type :invoke, :f :read, :value nil}))
(defn g-set-add  [_ _]
  (map (fn [x] {:type :invoke, :f :add, :value (str x)}) (range)))

(defn combine-workload-package-generators
  "Constructs a test generator by combining workload and package generators
   configures with CLI test opts"
  [opts workload package]

  (gen/phases
   (->> (:generator workload)
        (gen/stagger (/ (util/rand-int-from-range (:rate opts))))
        (gen/nemesis (:generator package))
        (gen/time-limit (:time-limit opts)))

   (:final-generator package)

   (:final-generator workload)))

(def workloads
  "A map of workload names to functions that construct workloads, given opts."
  {:g-set g-set/workload})

(def packages
  "A map of package names to functions that construct packages, given opts."
  {:g-set g-set/package})

(defn fuzz-dist-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
                      :concurrency, ...), constructs a test map."
  [opts]
  (let [db            (db/db :git)
        workload-name (:workload opts)
        workload      ((workloads workload-name) opts)
        package       ((packages  workload-name) opts)]

    (merge tests/noop-test
           opts
           {:name       (str "fuzz-dist"
                             "-Antidote"
                             "-" (count (:nodes opts)) "xdc"
                             "-" (str (seq (:faults opts))  "-at-" (util/pprint-ranges (:faults-times opts)) "s")
                             "-for-" (:time-limit opts) "s"
                             "-" (util/pprint-range (:rate opts)) "ts"
                             "-" workload-name)
            :os         debian/os
            :db         db
            :client     (:client workload)
            :nemesis    (:nemesis package)
            :pure-generators true
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
                          })})))

(def test-opt-spec
  "Options for tests."
  [["-w" "--workload NAME" "What workload to run."
    :default :g-set
    :parse-fn keyword
    :validate [workloads (cli/one-of workloads)]]])

(def opt-spec
  "Additional command line options."
  [[nil "--faults FAULTS" "A comma-separated list of specific faults to inject."
    :default #{:all}
    :parse-fn (fn [string]
                (->> (str/split string #"\s*,\s*")
                     (map keyword)
                     set))
    :validate [(partial every? nemesis/all-nemeses) (cli/one-of nemesis/all-nemeses)]]

   [nil "--faults-times [[QT,QT] [DT,DT]]" "Range of [[QuietMin, QuietMax] [DurationMin, DurationMax]] times."
    :default  [[1,5] [4,6]]
    :parse-fn read-string
    ;; TODO :validate [#(and (number? %) (pos? %)) "Must be a positive number"]
    ]

   ["-r" "--rate [HZ,HZ]" "Range of approximate number of requests per second, per thread."
    :default  [10,10]
    :parse-fn read-string
    ;; TODO :validate [#(and (number? %) (pos? %)) "Must be a positive number"]]
    ]])

(defn parse-faults
  "Post-processes :faults #{:all}, #{:none}."
  [parsed]
  (let [options (:options parsed)
        faults  (:faults options)]
    (assoc parsed :options (-> options
                               (assoc :faults (case faults
                                                #{:all} (set (keys nemesis/all-nemeses))
                                                #{:none} #{}
                                                faults))))))

(defn opt-fn
  "Post-processes the parsed CLI options structure."
  [parsed]
  (-> parsed
      parse-faults
      cli/test-opt-fn))

(defn all-tests
  "Takes parsed CLI options and constructs a sequence of test options, by
  combining all workloads, faults, fault durations, and rates."
  [opts]
  (let [faults                (:faults opts)
        [[quiet-min,quiet-max]
         [dur-min,dur-max]]   (:faults-times opts)
        quiets                (range quiet-min (+ quiet-max 1))
        durations             (range dur-min   (+ dur-max 1))
        [rate-min,rate-max]   (:rate opts)
        rates                 (range rate-min  (+ rate-max 1))
        workloads             [(:workload opts)]
        counts                (range (:test-count opts))]
    (->> (for [w workloads, f faults, q quiets, d durations, r rates, i counts]
           (assoc opts
                  :workload w
                  :faults #{f}
                  :faults-times [[q,q] [d,d]]
                  :rate [r,r]))
         (map fuzz-dist-test))))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
                browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  fuzz-dist-test
                                         :opt-spec (concat test-opt-spec opt-spec)
                                         :opt-fn*  opt-fn})
                   (cli/test-all-cmd    {:tests-fn all-tests
                                         :opt-spec (concat test-opt-spec opt-spec)
                                         :opt-fn*  opt-fn})
                   (cli/serve-cmd))
            args))
