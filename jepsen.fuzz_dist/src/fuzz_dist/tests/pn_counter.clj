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
  {:type :invoke, :f :read,      :value [key nil], :monotonic? true}  ; grow-only (can be applied to eventually, consistent? or final?)
  ```
   
  To create a `workload` with a suitable general purpose `generator`/`final-generator`,
  a random mix of counter strategies, and a full `checker` for use in your `test`,
  create a `Client` and:
   
  ```clj
  (defn workload
    [opts]
    (merge {:client (PNCounterClient. nil)}
           (pn-counter/test opts)))
  ```"
  (:refer-clojure :exclude [test])
  (:require [fuzz-dist.checker.offset :as offset]
            [jepsen
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
      (when (contains? #{:increment :decrement} f) ; TODO should only be :increments now?
        (grow-tree clone (txn->delta op))))
    clone))

(defn- history->counter-ranges
  "Augment all :read :ok and :increment :ok/:info ops with:
    :counter-ranges [counter, process]  ; ranges of all possible valid values during lifetime of op"
  ([history]
   (let [; ! mutable data structures !
         ; acceptable counter range(s)
         counter (TreeRangeSet/create [(vec->range [0 0])])
         ; each process has its own PoV that includes:
         ;   - :ok ops it did that happened
         ;   - :info ops it did that maybe? happened
         ;   - possibly any combination of :ok/:info ops done by other processes 
         process-views (->> (history/processes history)
                            (reduce (fn [acc p]
                                      (assoc acc p (TreeRangeSet/create [(vec->range [0 0])])))
                                    {}))]
     ; loop op by op
     ; - tracking open ops
     ; - updating counter and process PoV TreeRangeSets
     (loop [history       history
            open-ops      {}               ; {p op}
            history'      (transient [])]  ; augmented history
       (cond
         (nil? history)
         (persistent! history')

         :else ; op by op...
         (let [{:keys [process type f value] :as op} (first history)
               history (next  history)]

           (case [type f]
             [:invoke :read]
             ; open read op
             ;   - init :counter-ranges to counter, this process's view, plus any possible number of open :increments
             (do
               (assert (not (contains? open-ops process)) "Process already open!")
               (let [counter-ranges [(clone-tree counter                     (vals open-ops))
                                     (clone-tree (get process-views process) (vals open-ops))]
                     open-ops    (assoc open-ops
                                        process (assoc op :counter-ranges counter-ranges))
                     history'    (conj! history' op)]
                 (recur history open-ops history')))

             [:ok :read]
             ; close open op, augment op with :counter-ranges from open op
             (do
               (assert (contains? open-ops process) "Process not open!")
               (let [counter-ranges (get-in open-ops [process :counter-ranges])
                     open-ops       (dissoc open-ops process)
                     op'            (assoc op :counter-ranges counter-ranges)
                     history'       (conj! history' op')]
                 (recur history open-ops history')))

             [:invoke :increment]
             ; update any open ops to include the possibility of seeing this increment
             ; open :increment op with :counter-ranges init to:
             ;   counter and this process's view, with any possible number of open :increments
             ;   plus this op too
             (do
               (assert (not (contains? open-ops process)) "Process already open!")
               (let [_        (doseq [[_p {:keys [counter-ranges]}] open-ops]
                                (let [[ctr-ranges p-ranges] counter-ranges]
                                  (grow-tree ctr-ranges value)
                                  (grow-tree p-ranges   value)))
                     ; this op, in addition to the existing open ops, counts in init counter-ranges
                     [ctr-ranges, p-ranges] [(clone-tree counter                     (conj (vals open-ops) op))
                                             (clone-tree (get process-views process) (vals open-ops))]
                     _ (shift-tree p-ranges value) ; we see ourself
                     open-ops (assoc open-ops process (assoc op :counter-ranges [ctr-ranges p-ranges]))
                     history' (conj! history' op)]
                 (recur history open-ops history')))

             [:info :increment]
             ; update counter and all process PoVs as maybe?
             ; copy :counter-ranges from open op to augmented op
             ; close open op
             (do
               (assert (contains? open-ops process) "Process not open!")
               (let [_ (grow-tree counter value)
                     _ (doseq [[_p possible] process-views]
                         (grow-tree possible value))
                     counter-ranges (get-in open-ops [process :counter-ranges])
                     op'      (assoc op :counter-ranges  counter-ranges)
                     open-ops (dissoc open-ops process)
                     history' (conj! history' op')]
                 (recur history open-ops history')))

             [:ok :increment]
             ; op happened so shift counter, same for this process PoV,
             ;   but all other process Povs are maybe? due to eventually...
             ; copy :counter-ranges from open op to augmented op
             ; close open op
             (do
               (assert (contains? open-ops process) "Process not open!")
               (let [_ (shift-tree counter value)
                     _ (doseq [[p possible] process-views]
                         (if (= p process)
                           (shift-tree possible value)
                           (grow-tree possible value)))
                     counter-ranges (get-in open-ops [process :counter-ranges])
                     op'      (assoc op :counter-ranges  counter-ranges)
                     open-ops (dissoc open-ops process)
                     history' (conj! history' op')]
                 (recur history open-ops history')))

             ; ignore
             (let [history' (conj! history' op)]
               (recur history open-ops history')))))))))

(defn- history->counter-offsets
  "Augment a history with :read :counter-offsets {node offset}
   by interpreting :counter-ranges [counter process].
   Offset is the delta of the :value from either the lower or upper bounds
   of the acceptable counter range during the op."
  [history]
  (->> history
       (map (fn [{:keys [f type value counter-ranges node] :as op}]
              (if (and (= :read f)
                       (= :ok type)
                       (not (nil? counter-ranges))
                       (not (nil? node)))
                (let [[ctr-ranges _p-ranges] counter-ranges
                      read-offset (let [[lower upper]  (range->vec (.span ctr-ranges))]
                                    (cond
                                      (and (<= lower value)
                                           (<= value upper))
                                      0

                                      (< value lower)
                                      (- value lower)

                                      (< upper value)
                                      (- value upper)))]
                  (assoc op :counter-offsets {node read-offset}))
                op)))))

(defn- history->previous-reads
  "Given a history, returns a history with ok read ops augmented with :checker-prev-read.
   Reads are tracked per process."
  [history]
  (loop [history    history
         prev-reads {}      ; {process prev-read-value ...}
         history'   []]
    (cond
      (nil? history)
      history'

      :else ; op by op...
      (let [{:keys [type f value monotonic? process] :as op} (first history)
            history (next  history)]
        (if (and (= :read f)
                 (= :ok type)
                 monotonic?
                 process)
          (let [prev-read (get prev-reads process)
                prev-reads (assoc prev-reads process value)
                op' (assoc op :checker-prev-read prev-read)
                history' (conj history' op')]
            (recur history prev-reads history'))
          (let [history' (conj history' op)]
            (recur history prev-reads history')))))))

(defn- history->check-final-reads
  "Checks given history for valid final reads:
    - there are final? reads
    - all reads agree on counter ranges
    - all reads equal
  {:valid? true/false
   :final-reads [...]
   :counter [...]}"
  [history]
  (let [finals (->> history (filter #(and ((comp #{:read} :f) %)
                                          (:final? %)
                                          ((comp #{:ok} :type) %))))
        counters (->> finals (map (fn [{[ctr-range _p-range] :counter-ranges}]
                                    ctr-range)))
        counter (->> counters first)
        values (->> finals (map :value))
        errors (cond-> nil
                 (not (seq finals))
                 (conj {:checker-error :no-final-reads})

                 (-> counters set count (> 1))
                 (conj {:checker-error :final-counters-not-equal})

                 (-> values set count (> 1))
                 (conj {:checker-error :final-reads-not-equal}))]

    (assoc (if (nil? errors)
             {:valid? true}
             {:valid? false :errors errors})
           :final-reads values
           :counter (tree-range-set->vecs counter))))

(defn checker
  "Can be optionally bounded:
  ```clojure
  (checker {:bounds [lower upper]})  ; default [nil nil], i.e. (-∞..+∞)
  ```

  Returns:
  ```clojure
  {:valid?      true | false          ; any errors?
   :errors      [op, ...]             ; ops with errors
   :final-reads [value, ...]          ; all actual final? read value's
   :counter     [[lower upper]]       ; closed Range's of acceptable counter value's
   :read-range  [[lower upper]]       ; closed Range's of all actual read value's
   :bounds      [lower upper]         ; bounds, may be [nil nil] (-∞..+∞)
  }
  ```
  
  The `checker` goes through the history `op` by `op` keeping track of:
  
  - acceptable counter values
  - each client's possible PoV
  - what `op`'s definitely happened, maybe? happened 
  - what has each client definitely seen, maybe? seen
  - open `op`'s that may be seen during this `op`
  - and so on...
   
  `:ok` `:increment`/`:decrement` (happened)

  - must be reflected in the counter, this client's PoV
  - may be reflected in other client's PoV
  
  `:info` `:increment`/`:decrement` (maybe? happened)

  - may be reflected in the counter, this client's PoV
  - may be reflected in other client's PoV
  
open `:increment`/`:decrement` (regardless of ultimate `:ok`/`:info`)

  - may be reflected in the counter (not relevant for this client as it's doing the `op`)
  - may be reflected in other client's PoV
  
all `:reads`

  - must be possible from this client's PoV
  - must be in bounds

`:consistent?`/`:final?` `:reads`'s
  
  - must be acceptable counter value
  - `:final?` reads must be equal accross all clients

`:monotonic?` `:reads`'s
  
  - must be `>=` previous read (absolute values)
   
`:ok` `:increment`/`:decrement` (happened)

  - counter and this client's PoV must remain within bounds
  - other clients are maybe?, eventually consistent, so no check   

`:info` `:increment`/`:decrement` (maybe? happened)

  - no checks needed"
  ([] (checker {}))
  ([{bounds :bounds :as opts}]
   (reify checker/Checker
     (check [_this test history opts']
       (let [opts (merge opts opts')
             ^Range bounds (vec->range bounds)
             history (->> history
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
             ; augment 
             ;  - :ok/:info ops with :counter-ranges [counter process]
             ;  - :read :ok with :counter-offsets {node offset}, :checker-prev-read
             history (->> history
                          history->counter-ranges
                          history->counter-offsets
                          history->previous-reads
                          vec)

             ; loop op by op
             ; - testing values and building a sequence of any ops with errors
             errors
             (loop [history history
                    errors  []]
               (cond
                 (nil? history)
                 (->> errors ; remove augmented processing keys for readability
                      (map (fn [op] (dissoc op :counter-ranges :counter-offsets :checker-prev-read))))

                 :else ; op by op...
                 (let [{:keys [type f value consistent? final? monotonic? counter-ranges checker-prev-read] :as op} (first history)
                       history (next  history)
                       [ctr-ranges p-ranges] counter-ranges]

                   (case [type f]
                     [:ok :read]
                     ; read value possible for this process during time op was open?
                     ; :consistent?/:final? must also be in counter range
                     ; :monotonic? must absolutely grow
                     ; always in bounds
                     (let [errors (cond-> errors
                                    (not (.contains p-ranges value))
                                    (conj (assoc op :checker-error :value-not-possible))

                                    (and (or consistent? final?)
                                         (not (.contains ctr-ranges value)))
                                    (conj (assoc op :checker-error :value-not-acceptable))

                                    (and monotonic?
                                         (and checker-prev-read
                                              (< (abs value) (abs checker-prev-read))))
                                    (conj (assoc op :checker-error :value-not-monotonic))

                                    (not (.contains bounds value))
                                    (conj (assoc op :checker-error :value-out-of-bounds)))]
                       (recur history errors))

                     [:info :increment]
                     ; maybe? op occured, so can't test out of bounds 
                     (recur history errors)

                     [:ok :increment]
                     ; op occured, counter and process were updated, so
                     ; are process and counter values still within bounds
                     (let [errors (cond-> errors
                                    (not (.intersects ctr-ranges bounds))
                                    (conj (assoc op
                                                 :checker-error :counter-out-of-bounds))

                                    (not (.intersects p-ranges bounds))
                                    (conj (assoc op
                                                 :checker-error :process-out-of-bounds)))]
                       (recur history errors))

                     ; ignore
                     (recur history errors)))))

             final-reads (history->check-final-reads history)
             read-values (->> history
                              (filter  #(and ((comp #{:read} :f) %)
                                             ((comp #{:ok} :type) %)))
                              (history->value-ranges))
             plot     (offset/plot! test history (merge opts {:offset-key :counter-offsets
                                                              :plot-title (str  "Counter read Offsets for Key: " (:history-key opts))}))]

         {:valid?      (and (empty? errors)
                            (:valid? final-reads)
                            (:valid? plot))
          :errors      errors
          :final-reads final-reads
          :read-range  read-values
          :bounds      (range->vec bounds)
          :plot        plot})))))

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
   
  - `:f (only :increment *or* :decrement) :value (Fibonacci sequence)]`
  - `:f :read :value [key nil]`
   
  Transactions are augmented `:monotonic? true` for `checker` to validate."
  [key]
  (gen/mix [(pn-counter-adds-fib  [(rand-nth [:increment :decrement])] key)
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
          - period of low rate of read's only
      - then for every key, on every worker
          - `:read :final? true`

  Suggest `:rate` >= # keys * nodes * 4 for more effective coverage."
  [opts]
  (let [num-nodes (->> opts :nodes count)
        num-keys  num-nodes
        generators (reduce (fn [acc key]
                             (let [[k g] (rand-nth [[(str key "-rand")  (rand-value-generator  (str key "-rand")  1000)]
                                                    [(str key "-grow")  (grow-only-generator   (str key "-grow"))]
                                                    [(str key "-swing") (swing-value-generator (str key "-swing") 1000)]])]
                               (assoc acc k g)))
                           {}
                           (range 1 (+ 1 num-keys)))]
    {:generator (gen/mix (vals generators))
     :final-generator (gen/phases
                       (gen/log "Let database quiesce, slow rate of reads only...")
                       (->>
                        (fn []
                          (gen/once (pn-counter-reads (rand-nth (keys generators)) [])))
                        (gen/clients)
                        (gen/stagger (/ 1 (* num-keys num-nodes)))
                        (gen/time-limit 10))

                       (gen/sleep 1)

                       (gen/log "Final reads...")
                       (->>
                        (map (fn [key]
                               (gen/once (pn-counter-reads key [:final?])))
                             (keys generators))
                        (gen/each-thread)
                        (gen/clients)))}))

(defn test
  "Constructs a partial test for a `pn-counter`:
  ```clj
  {:generator
   :final-generator
   :checker}
  ```
  given options from the CLI test constructor.
   
  Generators and checker are `independent`, i.e. use key's,
  so must be paired with a Client that can handle `:value [key value]` `op`'s.
   
  See [[mix-generator]] and [[checker]]."
  [opts]
  (merge
   (mix-generator opts)
   {:checker (fn pn-checker ; support passing opts in test map, e.g. perf map for nemeses
               ([] (pn-checker {}))
               ([opts']
                (independent/checker (checker (merge opts opts')))))}))
