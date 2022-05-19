(ns fuzz-dist.util)

(def control-root "/home/jsuttor")
(def control-proj (str control-root "/" "projects"))
(def control-antidote (str control-proj "/" "antidote"))
(def control-fuzz-dist (str control-proj "/" "fuzz_dist" "/" "beam.fuzz_dist"))
(def node-antidote "/root/antidote")
(def node-antidote-log-file (str node-antidote "/" "antidote.daemon.log"))
(def node-antidote-pid-file (str node-antidote "/" "antidote.daemon.pid"))
(def node-fuzz-dist "/root/fuzz_dist")
(def node-fuzz-dist-log-file (str node-fuzz-dist "/" "fuzz_dist.daemon.log"))
(def node-fuzz-dist-pid-file (str node-fuzz-dist "/" "fuzz_dist.daemon.pid"))

(defn n-to-fqdn [node app] (let [[n num] node]
                             (str app "@" "192.168.122.10" num)))
(defn nodes-to-fqdn [nodes app] (map #(n-to-fqdn % app) nodes))

(defn rand-int-from-range
  ([[mi,ma]] (rand-int-from-range mi ma))
  ([mi ma]
   (+ mi
      (rand-int (+ (- ma
                      mi)
                   1)))))

(defn pprint-range
  [[from to]]
  (if (= from to)
    (str from)
    (str from "-" to)))

(defn pprint-ranges
  [ranges]
  (if (= (count ranges) 1)
    (pprint-range (first ranges))
    (reduce (fn [acc, range]
              (str acc "," (pprint-range range)))
            (str (pprint-range (first ranges)))
            (rest ranges))))