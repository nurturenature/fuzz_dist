(ns fuzz-dist.checker.offset
  "Helps analyze offsets over time."
  (:require  [clojure.core.reducers :as r]
             [clojure.string :as str]
             [jepsen
              [store :as store]
              [util :as util]]
             [jepsen.checker.perf :as perf]))

(defn history->datasets
  "Takes a history, and produces a map of nodes to sequences of [t offset]
  pairs, representing the changing offset values for that node over time."
  [history offset-key]
  (let [final-time (util/nanos->secs (:time (peek history)))]
    (->> history
         (r/filter offset-key)
         (reduce (fn [series op]
                   (let [t (util/nanos->secs (:time op))]
                     (reduce (fn [series [node offset]]
                               (let [s (get series node (transient []))
                                     s (conj! s [t offset])]
                                 (assoc! series node s)))
                             series
                             (get op offset-key))))
                 (transient {}))
         persistent!
         (util/map-vals (fn seal [points]
                          (-> points
                              (conj! (assoc (nth points (dec (count points)))
                                            0 final-time))
                              (persistent!)))))))

(defn short-node-names
  "Takes a collection of node names, and maps them to shorter names by removing
  common trailing strings (e.g. common domains)."
  [nodes]
  (->> nodes
       (map #(str/split % #"\."))
       (map reverse)
       util/drop-common-proper-prefix
       (map reverse)
       (map (partial str/join "."))))

(defn nodes->colors
  "Given a sequence of nodes, yields a map of node -> gnuplot-color, so we
  can render each node's offset in a different color."
  [nodes]
  (-> nodes
      sort
      reverse
      (zipmap (map vector
                   (repeat 'rgb)
                   (cycle ["red"
                           "orange"
                           "purple"
                           "blue"
                           "green"
                           "grey"])))))

(defn nodes->points
  "Given a sequence of node's, yields a map of node -> gnuplot-point-type, so we can
  render each node in a different style."
  [nodes]
  (->> nodes
       (map-indexed (fn [i n] [n (nth [1 2 3 4 6 8 10 12 14 16] i)]))
       (into {})))


(defn plot!
  "Plots offsets over time. Looks for any op with a `offset-key` field,
  which contains a (possible incomplete) map of nodes to offsets.
  Plots those offsets over time.
   
  ```clj
  opts {:offset-key  ; required, key in ops that has offset data
        :plot-output ; name of output file, suffix `.png` will be added, default \"offsets.png\"
        :plot-title  ; plot title, default \"Offsets\"
        :plot-ylabel ; y axis label, default \"offset\"
        }
  ```"
  [test history {:keys [offset-key plot-output plot-title plot-ylabel subdirectory nemeses]
                 :or {plot-output "offsets" plot-title "Offsets" plot-ylabel "offset"}
                 :as _opts}]
  (when (seq history)
    ; If the history is empty, don't render anything.
    (let [nemeses     (or nemeses (:nemeses (:plot test)))
          datasets    (history->datasets history offset-key)
          nodes       (util/polysort (keys datasets))
          node-names  (short-node-names nodes)
          output-path (.getCanonicalPath (store/path! test subdirectory (str plot-output ".png")))
          nodes->colors- (nodes->colors nodes)
          nodes->points- (nodes->points nodes)
          plot {:preamble (concat (perf/preamble output-path)
                                  [[:set :title plot-title]
                                   [:set :ylabel plot-ylabel]])
                :draw-fewer-on-top? true
                :series   (map (fn [node node-name]
                                 {:title node-name
                                  :with  'points
                                  :linetype  (nodes->colors- node)
                                  :pointtype (nodes->points- node)
                                  :data  (get datasets node)})
                               nodes
                               node-names)}]
      (when (perf/has-data? plot)
        (-> plot
            (perf/without-empty-series)
            (perf/with-range)
            (perf/with-nemeses history nemeses)
            (perf/plot!)))))
  {:valid? true})
