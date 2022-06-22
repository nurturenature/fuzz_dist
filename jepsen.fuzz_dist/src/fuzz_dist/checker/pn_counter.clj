(ns fuzz-dist.checker.pn-counter
  "A full checker for an eventually-consistent, optionally bounded,
  [Positive-Negative Counter](https://en.wikipedia.org/wiki/Conflict-free_replicated_data_type#PN-Counter_(Positive-Negative_Counter)).

  Clients are `invoke!`'d with the operations:
  ```clojure
  {:type :invoke, :f :increment, :value integer}
  {:type :invoke, :f :decrement, :value integer}
  {:type :invoke, :f :read}                      ; eventually-consistent
  {:type :invoke, :f :read, :consistent? true}   ; consistent
  {:type :invoke, :f :read, :final? true}        ; final (assumed consistent)
  ```
  
  Acceptable `:read` `:consistent?` | `:final?` `:value`'s are the sum of:

  - all `:ok` `:increment` | `:decrement`
  - any number of possibly-completed (`:info`) `:increment` | `:decrement`

  Possible `:read` `:value`'s, when eventually-consistent, are the sum of:

  - any number of `:ok` or possibly-completed (`:info`) `:increment` | `:decrement`

  Verifies every:

  - `:ok` `:read` `:value`
      - is in the set of possible ranges
      - is within bounds
  - `:ok` `:read` `:consistent?` | `:final?` `:value` 
      - is in the set of acceptable ranges
      - is within bounds
      - `:final?` `:value`'s are equal for all `:read`'s
  - `:ok` `:increment` | `:decrement`
      - acceptable counter value(s) remain within bounds

  TODO: Track read 'staleness', delta between `:read` `:value` and acceptable values?
        Plot it?
  "
  (:require [jepsen.checker :as checker]
            [knossos.op :as op]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import (com.google.common.collect Range
                                      RangeSet
                                      TreeRangeSet)))

(defn- range->vec
  "Converts an open range into a closed integer [lower upper] pair."
  [^Range r]
  [(inc (.lowerEndpoint r))
   (dec (.upperEndpoint r))])

(defn- acceptable->vecs
  "Turns an acceptable TreeRangeSet into a vector of [lower upper] inclusive
  ranges."
  [^TreeRangeSet s]
  (map range->vec (.asRanges s)))

(defn- acceptable-range
  "Takes a lower and upper bound for a range and constructs a Range for an
  acceptable TreeRangeSet. The constructed range will be an *open* range from
  lower - 1 to upper + 1, which ensures that merges work correctly."
  [lower upper]
  (Range/open (dec lower) (inc upper)))

(defn checker
  "Can be optionally bounded:
  ```clojure
  (checker {:bounds [lower upper]})
  ```
  Default bounds are `(-∞..+∞)`.

  Returns:
  ```clojure
  {:valid?      true | false          ; any errors?
   :errors      [trans, ...]          ; transactions with errors
   :final-reads [value, ...]          ; all actual :final? :read :value's
   :acceptable  [[lower upper]]       ; closed Range's of valid :final? :read :value's
   :read-range  [[lower upper]]       ; closed Range's of all actual :read :value's
   :bounds      Range                 ; bounds, may be (-∞..+∞), of counter
   :possible    [[lower upper], ...]] ; all possible Range's of :value's for an eventually-consistent :read
  }
  ```
  "
  ([] (checker {}))
  ([{bounds :bounds, :or {bounds [nil nil]}}]
   (reify checker/Checker
     (check [this test history opts]
       (let [[lower upper] bounds
             _      (assert (if (and lower upper)
                              (<= lower upper)
                              true))
             bounds (cond
                      (and lower       upper)       (acceptable-range lower upper)
                      (and lower       (not upper)) (Range/atLeast lower)
                      (and (not lower) upper)       (Range/atMost upper)
                      :else                         (Range/all))

             init-range (acceptable-range 0 0)

             ; ! mutable data structures !
             acceptable  (TreeRangeSet/create)
             _           (.add acceptable init-range)
             possible    (TreeRangeSet/create acceptable)
             read-values (TreeRangeSet/create)

             txns  (->> history
                        (filter #(or (and ((comp #{:increment :decrement} :f) %)
                                          (or (op/ok? %)
                                              (op/info? %)))
                                     (and ((comp #{:read} :f) %)
                                          (op/ok? %)))))
             state {:errors      (vec nil)
                    :final-reads (vec nil)}
             state (reduce (fn [{:keys [errors final-reads]              :as state}
                                {:keys [f type value final? consistent?] :as txn}]
                             (case f
                               :read
                               (if (integer? value)
                                 (do
                                   (.add read-values (acceptable-range value value))
                                   (assoc state
                                          :errors     (if (or (not (if (or final?
                                                                           consistent?)
                                                                     (.contains acceptable value)
                                                                     (.contains possible   value)))
                                                              (not (.contains bounds value)))
                                                        (conj errors
                                                              txn)
                                                        errors)
                                          :final-reads (if final?
                                                         (conj final-reads
                                                               txn)
                                                         final-reads)))
                                 (assoc state
                                        :errors (conj errors txn)))

                               (:increment :decrement)
                               (do
                                ; :ok   :increment | :decrement happened,
                                ;       recreate acceptable ranges as origninal plus delta.
                                ; :info maybe happened,
                                ;       leave existing acceptable ranges as is,
                                ;       add new ranges of existing plus delta
                                ; Always added to existing possible ranges.
                                ; 
                                ; At least one acceptable range must be in bounds after :increment | :decrement.
                                ;
                                ; Note we materialize asRanges to avoid iterating during our mutation!
                                 (let [orig-acceptable (vec (.asRanges acceptable))
                                       orig-possible   (vec (.asRanges possible))
                                       value           (if (= :increment f)
                                                         value
                                                         (* -1 value))]
                                   (if (= type :ok)
                                     (.clear acceptable))
                                   (doseq [^Range r orig-acceptable]
                                     (.add acceptable (Range/open (+ (.lowerEndpoint r) value)
                                                                  (+ (.upperEndpoint r) value))))
                                   (doseq [^Range r orig-possible]
                                     (.add possible   (Range/open (+ (.lowerEndpoint r) value)
                                                                  (+ (.upperEndpoint r) value)))))
                                 (assoc state
                                        :errors (if (not (some
                                                          #(.encloses bounds %)
                                                          (.asRanges acceptable)))
                                                  (conj errors txn)
                                                  errors)))))
                           state
                           txns)]
         (let [{:keys [errors, final-reads]} state
               final-read-values (map :value final-reads)
               errors (if (>= 1 (count (distinct final-read-values)))
                        errors
                        (conj errors final-reads))]
           {:valid?      (empty? errors)
            :errors      (seq errors)
            :final-reads final-read-values
            :acceptable  (acceptable->vecs acceptable)
            :read-range  (acceptable->vecs read-values)
            :bounds      (if (and (.hasLowerBound bounds)
                                  (.hasUpperBound bounds))
                           (range->vec bounds)
                           (.toString bounds))
            :possible    (acceptable->vecs possible)}))))))
