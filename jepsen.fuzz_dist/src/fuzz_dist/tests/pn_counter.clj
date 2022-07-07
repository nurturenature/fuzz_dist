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
            [knossos
             [history :as history]
             [op :as op]])
  (:import (com.google.common.collect Range
                                      TreeRangeSet)))

(defn- vec->range
  "Takes a [lower upper] *closed* bounds (nil = infinity) and constructs a Range.
  The constructed Range will be an *open* range from
  (lower - 1 .. upper + 1), which ensures that merges work correctly."
  (^Range [[lower upper]]
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
  (->> (map range->vec (.asRanges s))
       vec))

(defn- history->value-ranges
  "Takes a history and returns a vector of inclusive :value ranges."
  [history]
  (let [^TreeRangeSet values-set (TreeRangeSet/create (map #(vec->range [(:value %) (:value %)]) history))]
    (tree-range-set->vecs values-set)))

(defn- txn->delta
  "Given a transaction, returns `:value`/-`:value` for `:increment`/`:decrement`."
  [txn]
  (let [value  (:value txn)]
    (if ((comp #{:increment} :f) txn)
      value
      (* -1 value))))

(defn- shift-tree
  "Shift an existing TreeRangeSet to reflect delta in each Range."
  [^TreeRangeSet trs delta]
  ; ! Mutation ! materialize asRanges to avoid iterating during mutation!
  ;              clear tree as we only want shifted ranges
  (let [ranges (vec (.asRanges trs))
        _      (.clear trs)]
    (-> ranges
        (shift-ranges delta)
        (tree-range-set-adds trs))))

(defn- grow-tree
  "Grow an existing TreeRangeSet by adding new Range's that reflect the delta in each Range."
  [^TreeRangeSet trs delta]
  ; ! Mutation ! materialize asRanges to avoid iterating during mutation!
  (let [ranges (vec (.asRanges trs))]
    (-> ranges
        (shift-ranges delta)
        (tree-range-set-adds trs))))

(defn- clone-tree
  "Given a `TreeRangeSet` and ops, return a *new* tree with ops applied.
   Ops are applied as maybe? writes."
  ^TreeRangeSet [^TreeRangeSet trs ops]
  (let [clone (TreeRangeSet/create trs)]
    (doseq [{:keys [f] :as op} ops]
      (when (contains? #{:increment :decrement} f)
        (grow-tree clone (txn->delta op))))
    clone))

(defn- add-txn-error
  "Convenience to add an error to a transaction, and add transaction to errors."
  [msg txn errors]
  (->> msg
       (assoc txn :checker-error)
       (conj errors)))

(defn- history->non-monotonic-reads
  "Given a history, returns a vector of transactions that violate monotonic reads.
  
  `:read :monotonic? true :value [key value]` value's must be monotonic per process."
  [history]
  (let [{:keys [error-txns]}
        (->> history
             (filter #(and ((comp #{:read} :f) %)
                           (op/ok? %)
                           (:monotonic? %)))
             (remove #(nil? (:process %)))
             (reduce
              (fn [{:keys [last-reads error-txns] :as state} {:keys [process value] :as txn}]
                (let [prev  (get last-reads process)
                      state (assoc state :last-reads (assoc last-reads process value))]
                  (if (and prev
                           (< (abs value) (abs prev)))
                    (assoc state :error-txns (add-txn-error (str "non-monotonic read, prev: " prev) txn error-txns))
                    state)))
              {:last-reads  {}
               :error-txns []}))]
    error-txns))

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
      - is increasing if `:monotonic? true`, e.g. grow-only-counter
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
  ([{bounds :bounds}]
   (reify checker/Checker
     (check [_this _test history _opts]
       (let [^Range bounds (vec->range bounds)
             history (->> history
                          (remove #(= :nemesis (:process %)))
                          history/complete
                          history/without-failures)
             ; remove read infos and their invokes
             pair-index (history/pair-index+ history)
             history (->> history
                          (remove #(and (= :read (:f %))
                                        (or (and (op/invoke? %)
                                                 (op/info? (history/completion pair-index %)))
                                            (op/info? %)))))
             ; convert all :decrements to :increments
             history (->> history
                          (map (fn [{:keys [f value] :as op}]
                                 (if (= :decrement f)
                                   (assoc op
                                          :f :increment
                                          :value (* -1 value))
                                   op)))
                          vec)

             read-values (->> history
                              (filter  #(and ((comp #{:read} :f) %)
                                             ((comp #{:ok} :type) %)))
                              (history->value-ranges))

             final-read-values (map :value (->> history
                                                (filter #(and ((comp #{:read} :f) %)
                                                              (:final? %)
                                                              ((comp #{:ok} :type) %)))))

             ; ! mutable data structures !
             ; acceptable counter range(s)
             ^TreeRangeSet counter (TreeRangeSet/create [(vec->range [0 0])])
             ; each process has its own PoV
             ;   - the ops it did for sure, and
             ;   - possibly any combination of ops done by other processes 
             process-views (->> (history/processes history)
                                (reduce (fn [acc p]
                                          (assoc acc p (TreeRangeSet/create [(vec->range [0 0])])))
                                        {}))

             ; loop op by op
             ; - tracking open ops
             ; - updating counter and process PoV TreeRangeSets
             ; - testing values and building a sequence of any ops with errors
             errors (loop [history       history
                           open-ops      {}            ; {p op}
                           process-views process-views ; {p possible-ranges}
                           errors        []]
                      (cond
                        (and (nil? history) (empty? open-ops))
                        errors

                        (and (nil? history) (seq open-ops))
                        (concat errors
                                (->> open-ops
                                     (map (fn [op]
                                            (assoc op :checker-error :uncompleted-op)))))

                        :else ; op by op...
                        (let [{:keys [process type f value consistent? final?] :as op} (first history)
                              history (next  history)]

                          (case [type f]
                            [:invoke :read]
                            ; open read op with :checker-valids init to this process's view, plus any possible number of open :increments
                            (do
                              (assert (not (contains? open-ops process)) "Process already open!")
                              (let [valid-reads (clone-tree (get process-views process) (vals open-ops))
                                    open-ops    (assoc open-ops
                                                       process (assoc op :checker-valids valid-reads))]
                                (recur history open-ops process-views errors)))

                            [:ok :read]
                            ; read value possible/acceptable during the time transaction was open?, close open op
                            (do
                              (assert (contains? open-ops process) "Process not open!")
                              (let [errors (case [(.contains (get-in open-ops [process :checker-valids]) value)
                                                  (or (not (or consistent? final?))
                                                      (.contains counter value))]
                                             [true  true]  errors
                                             [false true]  (conj errors (assoc op
                                                                               :checker-error :value-not-possible
                                                                               :checker-valids (tree-range-set->vecs (get-in open-ops [process :checker-valids]))))
                                             [true  false] (conj errors (assoc op
                                                                               :checker-error :value-not-acceptable
                                                                               :checker-valids (tree-range-set->vecs counter)))
                                             [false false] (conj errors (assoc op
                                                                               :checker-error [:value-not-possible :value-not-acceptable]
                                                                               :checker-valids [(tree-range-set->vecs (get-in open-ops [process :checker-valids]))
                                                                                                (tree-range-set->vecs counter)]))
                                             errors)
                                    errors (if (not (.contains bounds value))
                                             (conj errors (assoc op :checker-error :value-out-of-bounds))
                                             errors)
                                    open-ops (dissoc open-ops process)]
                                (recur history open-ops process-views errors)))

                            [:invoke :increment]
                              ; open :increment transaction, update any open reads to include the possibility of seeing this increment
                            (do
                              (assert (not (contains? open-ops process)) "Process already open!")
                              (let [open-ops (assoc open-ops process op)
                                    _        (doseq [[_p {:keys [f checker-valids]}] open-ops]
                                               (when (= :read f)
                                                 (grow-tree checker-valids value)))]
                                (recur history open-ops process-views errors)))

                            [:info :increment]
                            ; close :increment transaction, update counter as maybe, and all process as possibles
                            (do
                              (assert (contains? open-ops process) "Process not open!")
                              (let [open-ops (dissoc open-ops process)
                                    _ (grow-tree counter value)
                                    _ (doseq [[_p possible] process-views]
                                        (grow-tree possible value))
                                    ; no bounds check, op is a maybe?
                                    ]
                                (recur history open-ops process-views errors)))

                            [:ok :increment]
                              ; close :increment transaction, shift counter as trans happened, same for this process, and all other process are possibles
                            (do
                              (assert (contains? open-ops process) "Process not open!")
                              (let [open-ops (dissoc open-ops process)
                                    _ (shift-tree counter value)
                                    _ (doseq [[p possible] process-views]
                                        (if (= p process)
                                          (shift-tree possible value)
                                          (grow-tree possible value)))
                                    errors (if (not (.intersects counter bounds))
                                             (conj errors (assoc op
                                                                 :checker-error :counter-out-of-bounds
                                                                 :checker-valids (tree-range-set->vecs counter)))
                                             errors)]
                                (recur history open-ops process-views errors)))

                            (let [errors (conj errors (assoc op :checker-error :unknown-op))]
                              (recur history open-ops process-views errors))))))

             errors  (reduce (fn [acc txn] (conj acc txn)) errors (history->non-monotonic-reads history))
             errors  (if (< 1 (count (distinct final-read-values)))
                       (conj errors {:checker-error :unequal-final-reads :final-reads final-read-values})
                       errors)]

         {:valid?      (empty? errors)
          :errors      errors
          :final-reads final-read-values
          :acceptable  (tree-range-set->vecs counter)
          :read-range  read-values
          :bounds      (range->vec bounds)})))))

(defn- pn-counter-adds
  "Random mix of `ops`, e.g. `[:increment, :decrement]` with `:value [key, lower <= random <= upper]`."
  [ops key [lower upper]]
  (fn [] {:type :invoke,
          :f (rand-nth ops),
          :value (independent/tuple key (+ lower (rand-int (+ 1 (- upper lower)))))}))

(defn- pn-counter-reads
  "Sequence of `:read`'s, optionally marked `:flag? true`."
  [k flags]
  (fn []
    (reduce (fn [txn flag] (assoc txn flag true))
            {:type :invoke,
             :f :read,
             :value (independent/tuple k nil)}
            flags)))

(defn rand-value-generator
  "Generate random `:increment`/`:decrement` with random values.
   
  Returns a `generator/mix` of:
   
  - `:f (random :increment/:decrement) :value [key (-value <= random <= value)]`
  - `:f :read :value [key nil]`"
  [key value]
  (gen/mix [(pn-counter-adds [:increment :decrement]  key [(* -1 value) value])
            (pn-counter-reads key [])]))

(defn grow-only-generator
  "Generator for a grow-only counter.
  
  Returns a `generator/mix` of
   
  - `:f (only :increment *or* :decrement) :value [key (0 <= random <= value)]`
  - `:f :read :value [key nil]`
   
  Transactions are augmented `:monotonic? true` for `checker` to validate."
  [key value]
  (gen/mix [(pn-counter-adds  [(rand-nth [:increment :decrement])] key [0 value])
            (pn-counter-reads key [:monotonic?])]))

(defn swing-value-generator
  "Generator that swings between trying to increase the counter with `:increment`'s,
  then decrease with `:decrement`s, then increase ...

  Returns a `generator/mix` of
   
  - `:f (periods of :increment, then :decrement, then ...) :value [key (0 <= random <= value)]`
  - `:f :read :value [key nil]`"
  [key value]
  (gen/cycle-times 10 (gen/mix [(pn-counter-adds [:increment]  key [0 value])
                                (pn-counter-reads key [])])
                   10 (gen/mix [(pn-counter-adds [:decrement]  key [0 value])
                                (pn-counter-reads key [])])))

(defn mix-generator
  "Returns `{:generator, :final-generator}` where:
  
  - `:generator` is a `generator/mix` of individual generators
      - 1 generator / key, with  # keys = nodes
      - each individual key generator:
          - uses a different strategy to generate operations
              - random, swing, grow-only
          - is active the entire time of the test
  - `:final-generator` is shared/common
      - quiesce
      - for every key, on every worker
          - `:read :final? true`

  Suggest `:rate` >= # keys * nodes * 4 for more effective coverage."
  [opts]
  (let [num-keys (->> opts :nodes count)
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
                               (gen/once (pn-counter-reads key [:final?])))
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
