(ns fuzz-dist.tests.pn-counter
  "Tests for an eventually-consistent, optionally bounded,
  [Positive-Negative Counter](https://en.wikipedia.org/wiki/Conflict-free_replicated_data_type#PN-Counter_(Positive-Negative_Counter)).

  Clients are `invoke!`'d with the operations:
  ```clojure
  {:type :invoke, :f :increment, :value [key integer]}
  {:type :invoke, :f :decrement, :value [key integer]}
  {:type :invoke, :f :read       :value [key nil]}                    ; eventually-consistent
  {:type :invoke, :f :read,      :value [key nil], :consistent? true} ; consistent
  {:type :invoke, :f :read,      :value [key nil], :final? true}      ; final (assumed consistent)
  ```
  
  TODO: Track read 'staleness', delta between `:read` `:value` and acceptable values?
        Plot it?
  "
  (:refer-clojure :exclude [test])
  (:require [jepsen
             [checker :as checker]
             [generator :as gen]
             [independent :as independent]]
            [knossos.op :as op])
  (:import (com.google.common.collect Range
                                      TreeRangeSet)))

(defn- bounds->range
  "Takes a lower and upper *closed* bound (nil = infinity) and constructs a Range.
  The constructed Range will be an *open* range from
  lower - 1 to upper + 1, which ensures that merges work correctly."
  (^Range [value] (bounds->range value value))
  (^Range [lower upper]
   (assert (if (and lower upper)
             (<= lower upper)
             true))
   (cond
     (and lower       upper)       (Range/open (dec lower) (inc upper))
     (and lower       (not upper)) (Range/atLeast lower)
     (and (not lower) upper)       (Range/atMost upper)
     :else                         (Range/all))))

(defn- range->vec
  "Converts an open range into a closed integer [lower upper] pair."
  [^Range r]
  (cond
    (and (.hasLowerBound r)
         (.hasUpperBound r))
    [(inc (.lowerEndpoint r))
     (dec (.upperEndpoint r))]

    (and (.hasLowerBound r)
         (not (.hasUpperBound r)))
    [(inc (.lowerEndpoint r))
     nil]

    (and (not (.hasLowerBound r))
         (.hasUpperBound r))
    [nil
     (dec (.upperEndpoint r))]

    :else [nil nil]))

(defn- shift-range
  "Creates a new Range from existing Range + delta."
  ^Range [^Range r delta]
  (Range/open (+ (.lowerEndpoint r) delta)
              (+ (.upperEndpoint r) delta)))

(defn- shift-ranges
  "Creates a new sequence of Range's by shifting existing Range's by delta."
  [ranges delta]
  (map #(shift-range % delta) ranges))

(defn- tree-range-set-adds
  "Adds the given Range's to an existing TreeRangeSet"
  [ranges ^TreeRangeSet trs]
  (doseq [^Range r ranges]
    (.add trs r)))

(defn- tree-range-set->vecs
  "Turns a TreeRangeSet of open Range's into a 
  vector of closed, inclusive, [lower upper] ranges."
  [^TreeRangeSet s]
  (map range->vec (.asRanges s)))

(defn- history->value-ranges
  "Takes a history and returns a vector of inclusive :value ranges."
  [history]
  (let [^TreeRangeSet values-set (TreeRangeSet/create (map #(bounds->range (:value %)) history))]
    (tree-range-set->vecs values-set)))

(defn- txn->delta
  "Given a transaction, returns `:value`/-`:value` for `:increment`/`:decrement`."
  [txn]
  (let [value  (:value txn)]
    (if ((comp #{:increment} :f) txn)
      value
      (* -1 value))))

(defn- grow-tree
  "Grow an existing TreeRangeSet to reflect delta in each Range.
  prune? true  -> resulting tree will only contain shifted Range's.
  prune? false -> resulting tree will contain original Range's and shifted Range's."
  [^TreeRangeSet trs delta prune?]
  ; ! Mutation ! materialize asRanges to avoid iterating during mutation!
  (let [orig-ranges (vec (.asRanges trs))]
    (if prune?
      (.clear trs))
    (-> orig-ranges
        (shift-ranges delta)
        (tree-range-set-adds trs))))

(defn- add-txn-error
  "Convenience to add an error to a transaction, and add transaction to errors."
  [msg txn errors]
  (->> msg
       (assoc txn :checker-error)
       (conj errors)))

(defn checker
  "Can be optionally bounded:
  ```clojure
  (checker {:bounds [lower upper]})  ; default [nil nil], i.e. (-∞..+∞)
  ```

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
  
  Verifies every:

  - `:ok` `:read` `:value`
      - is in the set of *possible* ranges
      - is within bounds
  - `:ok` `:read` `:consistent?`/`:final?` `:value` 
      - is in the set of *acceptable* ranges
      - is within bounds
      - `:final?` `:value`'s are equal for all `:read`'s
  - `:ok` `:increment`/`:decrement` `:value`
      - *acceptable* counter value(s) remain within bounds
 
  *Acceptable* `:read` `:consistent?`/`:final?` `:value`'s are the sum of:

  - all `:ok` `:increment`/`:decrement`
  - any number of possibly-completed (`:info`) `:increment`/`:decrement`
  - any number of open `:increment`/`:decrement` transactions, e.g. `:invoke`'d but not yet `:ok`/`:info`/`:fail`

  *Possible* `:read` `:value`'s when eventually-consistent are the sum of:

  - any number of `:ok` `:increment`/`:decrement`
  - any number of possibly-completed (`:info`) `:increment`/`:decrement`
  - any number of open `:increment`/`:decrement` transactions, e.g. `:invoke`'d but not yet `:ok`/`:info`/`:fail`
"
  ([] (checker {}))
  ([{[lower upper] :bounds}]
   (reify checker/Checker
     (check [_this _test history _opts]
       (let [^Range bounds (bounds->range lower upper)

             ; ! mutable data structures !
             ^TreeRangeSet acceptable (TreeRangeSet/create [(bounds->range 0 0)])
             ^TreeRangeSet possible   (TreeRangeSet/create [(bounds->range 0 0)])

             txns  (->> history
                        (filter #(or ((comp #{:increment :decrement} :f) %)
                                     (and ((comp #{:read} :f) %)
                                          (op/ok? %)))))
             state (reduce
                    (fn [{:keys [errors open-txn] :as state}
                         {:keys [f type value consistent? final? process] :as txn}]
                      (cond
                        (and (= :read f)
                             (not (integer? value)))
                        (assoc state :errors
                               (add-txn-error "non integer value" txn errors))


                        (and (= :read f)
                             (not (or consistent? final?)))
                        (let [^TreeRangeSet possible-open (TreeRangeSet/create possible)
                              _ (doseq [txn (vals open-txn)]
                                  (grow-tree possible-open (txn->delta txn) false))]
                          (cond
                            (not (.contains possible-open value))
                            (assoc state :errors
                                   (add-txn-error (str "value not possible: " (vec (tree-range-set->vecs possible-open)))
                                                  txn errors))

                            (not (.contains bounds value))
                            (assoc state :errors
                                   (add-txn-error (str "value out of bounds: " (range->vec bounds))
                                                  txn errors))

                            :else state))

                        (and (= :read f)
                             (or consistent? final?))
                        (let [^TreeRangeSet acceptable-open (TreeRangeSet/create acceptable)
                              _ (doseq [txn (vals open-txn)]
                                  (grow-tree acceptable-open (txn->delta txn) false))]
                          (cond
                            (not (.contains acceptable-open value))
                            (assoc state :errors
                                   (add-txn-error (str "value not acceptable: " (vec (tree-range-set->vecs acceptable-open)))
                                                  txn
                                                  errors))

                            (not (.contains bounds value))
                            (assoc state :errors
                                   (add-txn-error (str "value out of bounds: " (range->vec bounds))
                                                  txn
                                                  errors))

                            :else state))

                        (and (f #{:increment :decrement})
                             (= type :invoke))
                        ; track as open transaction, e.g. may or may not have happened
                        ; relative to an interleaved :read
                        (do (assert (not (contains? open-txn process)) "process already open!")
                            (assoc state
                                   :open-txn (assoc open-txn process txn)))

                        (and (f #{:increment :decrement})
                             (= type :fail))
                        ; did not happen, close
                        (do (assert (contains? open-txn process) "process not open!")
                            (assoc state
                                   :open-txn (dissoc open-txn process)))

                        (and (f #{:increment :decrement})
                             (contains? #{:ok :info} type))
                        ; For acceptable Range's:
                        ; :ok     - :increment/:decrement happened,
                        ;           prune tree and add shifted by delta Range's
                        ; :info   - :increment/:decrement maybe happened,
                        ;           leave existing ranges and add shifted by delta Range's
                        ;
                        ; Always added to existing possible ranges.
                        ; 
                        ; At least one acceptable range must be in bounds after :increment/:decrement.
                        (let [open-txn (dissoc open-txn process)
                              delta    (txn->delta txn)]
                          (grow-tree acceptable delta (= type :ok))
                          (grow-tree possible   delta false)
                          (assoc state
                                 :errors (if (not (some
                                                   #(.encloses bounds %)
                                                   (.asRanges acceptable)))
                                           (add-txn-error (str "value out of bounds: " (range->vec bounds))
                                                          txn errors)
                                           errors)
                                 :open-txn open-txn))))
                    {:errors      (vec nil)
                     :open-txn    {}}
                    txns)

             read-values (->> txns
                              (filter  #((comp #{:read} :f) %))
                              (history->value-ranges))
             final-read-values (map :value (->> txns
                                                (filter #(and ((comp #{:read} :f) %)
                                                              (:final? %)))))

             errors  (:errors state)
             errors (if (< 1 (count (distinct final-read-values)))
                      (conj errors {:checker-error "unequal final reads" :value final-read-values})
                      errors)]

         {:valid?      (empty? errors)
          :errors      errors
          :final-reads final-read-values
          :acceptable  (tree-range-set->vecs acceptable)
          :read-range  read-values
          :bounds      (if (and (.hasLowerBound bounds)
                                (.hasUpperBound bounds))
                         (range->vec bounds)
                         (.toString bounds))
          :possible    (tree-range-set->vecs possible)})))))

(defn- pn-counter-adds
  "Random mix of `ops`, e.g. `[:increment, :decrement]` with `:value [key, lower <= random <= upper]`."
  [ops key [lower upper]]
  (fn [] {:type :invoke,
          :f (rand-nth ops),
          :value (independent/tuple key (+ lower (rand-int (+ 1 (- upper lower)))))}))

(defn- pn-counter-reads
  "Sequence of `:read`'s, optionally marked `:final? true`."
  [k final?]
  (if final?
    (repeat {:type :invoke, :f :read, :value (independent/tuple k nil), :final? true})
    (repeat {:type :invoke, :f :read, :value (independent/tuple k nil)})))

(defn rand-value-generator
  "Generate random `:increment`/`:decrement` with random values.
   
  Returns a `generator/mix` of:
   
  - `:f (random :increment/:decrement) :value [key (-value <= random <= value)]`
  - `:f :read :value [key nil]`"
  [key value]
  (gen/mix [(pn-counter-adds [:increment :decrement]  key [(* -1 value) value])
            (pn-counter-reads key false)]))

(defn grow-only-generator
  "Generator for a grow-only counter.
  
  Returns a `generator/mix` of
   
  - `:f (only :increment *or* :decrement) :value [key (0 <= random <= value)]`
  - `:f :read :value [key nil]`
   
  TODO: augment checker to test monotonicity per process."
  [key value]
  (gen/mix [(pn-counter-adds  [(rand-nth [:increment :decrement])] key [0 value])
            (pn-counter-reads key false)]))

(defn swing-value-generator
  "Generator that swings between trying to increase the counter with `:increment`'s,
  then decrease with `:decrement`s, then increase ...

  Returns a `generator/mix` of
   
  - `:f (periods of :increment, then :decrement, then ...) :value [key (0 <= random <= value)]`
  - `:f :read :value [key nil]`"
  [key value]
  (gen/cycle-times 10 (gen/mix [(pn-counter-adds [:increment]  key [0 value])
                                (pn-counter-reads key false)])
                   10 (gen/mix [(pn-counter-adds [:decrement]  key [0 value])
                                (pn-counter-reads key false)])))

(defn mix-generator
  "Returns `{:generator, :final-generator}` where:
  
  - `:generator` is a `generator/mix` of individual generators
      - 1 generator / key, with  # keys = nodes * 2
      - each individual key generator:
          - uses a different strategy to generate operations
              - random, swing, grow-only
          - is active the entire time of the test
  - `:final-generator` is shared/common
      - quiesce
      - for every key, on every worker
          - `:read :final? true`

  Suggest `:rate` >= # keys * nodes * 2 for more effective coverage."
  [opts]
  (let [num-keys (->> opts :nodes count (* 2))
        generators (reduce (fn [acc key]
                             (let [[k g] (rand-nth [[(str key "-rand")  (rand-value-generator  (str key "-rand")  1000)]
                                                    [(str key "-grow")  (grow-only-generator   (str key "-grow")  1000)]
                                                    [(str key "-swing") (swing-value-generator (str key "-swing") 1000)]])]
                               (assoc acc k g)))
                           {}
                           (range 1 (+ 1 num-keys)))]
    {:generator (gen/mix (vals generators))
     :final-generator (gen/phases
                       (gen/log "Let database quiesce...")
                       (gen/sleep 10)

                       (gen/log "Final reads...")
                       (->>
                        (map (fn [key]
                               (gen/once (pn-counter-reads key true)))
                             (keys generators))
                        (gen/each-thread)
                        (gen/clients)))}))

(defn test
  "Constructs a partial test:
  ```clojure
  {:generator
   :final-generator
   :checker}
  ```
  given options from the CLI test constructor.
   
  Generators and checker are `independent`, e.g. key aware, `:value [key value]`.
   
  So clients must `:invoke!` ops with `:value [key value]`."
  [opts]
  (merge
   {:checker (independent/checker (checker))}
   (mix-generator opts)))
