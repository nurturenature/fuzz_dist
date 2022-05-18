(ns fuzz-dist.util
  (:require [clojure.string :as str]))

(def control-root "/home/jsuttor")
(def control-proj (str control-root "/" "projects"))
(def control-antidote (str control-proj "/" "antidote"))
(def control-fuzz-dist (str control-proj "/" "fuzz_dist" "/" "beam.fuzz_dist"))
(def node-antidote "/root/antidote")
(def node-fuzz-dist "/root/fuzz_dist")

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