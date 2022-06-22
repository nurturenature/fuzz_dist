(ns fuzz-dist.checker.pn-counter-test
  (:require [clojure [pprint :refer [pprint]]
             [test :refer :all]]
            [jepsen.checker :as checker]
            [fuzz-dist.checker.pn-counter :as pn-counter]))

(let [check        (fn [history] (checker/check (pn-counter/checker)                    {} history {}))
      check-bounds (fn [history] (checker/check (pn-counter/checker  {:bounds [0 100]}) {} history {}))]
  (deftest checker-test
    (testing "empty"
      (is (= {:valid?      true
              :errors      nil
              :final-reads []
              :acceptable  [[0 0]]
              :read-range  []
              :bounds      "(-∞..+∞)"
              :possible    [[0 0]]}
             (check []))))

    (testing "definite"
      (is (= {:valid?      false
              :errors      [{:type :ok, :f :read, :final? true, :value 4}
                            [{:type :ok, :f :read, :final? true, :value 5}
                             {:type :ok, :f :read, :final? true, :value 4}]]
              :final-reads [5 4]
              :acceptable  [[5 5]]
              :read-range  [[4 5]]
              :bounds      "(-∞..+∞)"
              :possible    [[0 0] [2 3] [5 5]]}
             (check [{:type :ok, :f :increment, :value 2}
                     {:type :ok, :f :increment, :value 3}
                     {:type :ok, :f :read, :final? true, :value 5}
                     {:type :ok, :f :read, :final? true, :value 4}]))))

    (testing "indefinite"
      (is (= {:valid?      false
              :errors      [{:type :ok, :f :read, :final? true, :value 11}
                            [{:type :ok, :f :read, :final? true, :value 11}
                             {:type :ok, :f :read, :final? true, :value 15}]]
              :final-reads [11 15]
              :acceptable  [[8 10] [13 15]]
              :read-range  [[11 11] [15 15]]
              :bounds      "(-∞..+∞)"
              :possible    [[-2 0] [3 5] [8 10] [13 15]]}
             (check [{:type :ok,   :f :increment, :value 10}
                     {:type :info, :f :increment, :value 5}
                     {:type :info, :f :increment, :value -1}
                     {:type :info, :f :increment, :value -1}
                     {:type :ok,   :f :read, :final? true, :value 11}
                     {:type :ok,   :f :read, :final? true, :value 15}]))))

    (testing "invalid-non-final-read"
      (is (= {:valid?      false
              :errors      [{:type :ok, :f :read, :value 4}
                            [{:type :ok, :f :read, :final? true, :value 1}
                             {:type :ok, :f :read, :final? true, :value 4}]]
              :final-reads [1 4]
              :acceptable  [[1 4]]
              :read-range  [[1 1] [4 4]]
              :bounds      "(-∞..+∞)"
              :possible    [[-1 5]]}
             (check [{:type :ok,   :f :increment, :value 1}
                     {:type :info, :f :increment, :value 1}
                     {:type :info, :f :increment, :value 1}
                     {:type :ok,   :f :read,      :value 4}
                     {:type :info, :f :increment, :value 1}
                     {:type :ok,   :f :increment, :value 1}
                     {:type :ok,   :f :increment, :value -1}
                     {:type :ok,   :f :read,      :final? true, :value 1}
                     {:type :ok,   :f :read,      :final? true, :value 4}]))))

    (testing "possible-final-reads-but-not-equal"
      (is (= {:valid?      false
              :errors      [[{:type :ok, :f :read, :final? true, :value 1}
                             {:type :ok, :f :read, :final? true, :value 2}]]
              :final-reads [1 2]
              :acceptable  [[1 2]]
              :read-range  [[1 2]]
              :bounds      "(-∞..+∞)"
              :possible    [[0 2]]}
             (check [{:type :ok,   :f :increment,  :value 1}
                     {:type :info, :f :increment,  :value 1}
                     {:type :ok,   :f :read, :final? true, :value 1}
                     {:type :ok,   :f :read, :final? true, :value 2}]))))

    (testing "consistent"
      (is (= {:valid?      true
              :errors      nil
              :final-reads [2]
              :acceptable  [[2 3]]
              :read-range  [[0 2]]
              :bounds      "(-∞..+∞)"
              :possible    [[0 3]]}
             (check [{:type :ok,   :f :increment, :value 1}
                     {:type :ok,   :f :read, :value 0}
                     {:type :ok,   :f :read, :value 1, :consistent? true}
                     {:type :info, :f :increment, :value 1}
                     {:type :ok,   :f :read, :value 1}
                     {:type :ok,   :f :read, :value 2, :consistent? true}
                     {:type :ok,   :f :increment, :value 1}
                     {:type :ok,   :f :read, :value 2, :final? true}]))))

    (testing "possible-not-consistent"
      (is (= {:valid?      false
              :errors      [{:type :ok, :f :read, :value 1, :consistent? true}]
              :final-reads [2]
              :acceptable  [[2 3]]
              :read-range  [[0 2]]
              :bounds      "(-∞..+∞)"
              :possible    [[0 3]]}
             (check [{:type :ok,   :f :increment,  :value 1}
                     {:type :ok,   :f :read, :value 0}
                     {:type :ok,   :f :read, :value 1, :consistent? true}
                     {:type :info, :f :increment, :value 1}
                     {:type :ok,   :f :read, :value 1}
                     {:type :ok,   :f :read, :value 2, :consistent? true}
                     {:type :ok,   :f :increment, :value 1}
                     {:type :ok,   :f :read, :value 1, :consistent? true}
                     {:type :ok,   :f :read, :value 2, :final? true}]))))

    (testing "increment-decrement"
      (is (= {:valid?      true
              :errors      nil
              :final-reads [2 2]
              :acceptable  [[2 2] [4 4]]
              :read-range  [[-1 -1] [1 2]]
              :bounds      "(-∞..+∞)"
              :possible    [[-2 4]]}
             (check [{:type :ok,   :f :increment, :value 1}
                     {:type :ok,   :f :read, :value 1}
                     {:type :info, :f :decrement, :value 2}
                     {:type :ok,   :f :read, :value -1}
                     {:type :ok,   :f :increment, :value 3}
                     {:type :ok,   :f :read, :value 2, :final? true}
                     {:type :ok,   :f :read, :value 2, :final? true}]))))

    (testing "bounded-empty"
      (is (= {:valid?      true
              :errors      nil
              :final-reads []
              :acceptable  [[0 0]]
              :read-range  []
              :bounds      [0 100]
              :possible    [[0 0]]}
             (check-bounds []))))

    (testing "possible-reads-out-of-bounds"
      (is (= {:valid?      false
              :errors      [{:type :ok, :f :read, :value 200}
                            {:type :ok, :f :read, :final? true, :value 200}
                            [{:type :ok, :f :read, :final? true, :value 100}
                             {:type :ok, :f :read, :final? true, :value 200}]]
              :final-reads [100 200]
              :acceptable  [[100 100] [200 200]]
              :read-range  [[100 100] [200 200]]
              :bounds      [0 100]
              :possible    [[0 0] [100 100] [200 200]]}
             (check-bounds [{:type :ok,   :f :increment, :value 100}
                            {:type :ok,   :f :read, :value 100}
                            {:type :info, :f :increment, :value 100}
                            {:type :ok,   :f :read, :value 200}
                            {:type :ok,   :f :read, :final? true, :value 100}
                            {:type :ok,   :f :read, :final? true, :value 200}]))))

    (testing "increments-out-of-bounds"
      (is (= {:valid?      false
              :errors      [{:type :ok, :f :increment,  :value 100}
                            {:type :ok, :f :read, :value 200}
                            {:type :ok, :f :read, :final? true, :value 100}
                            {:type :ok, :f :read, :final? true, :value 200}
                            [{:type :ok, :f :read, :final? true, :value 100}
                             {:type :ok, :f :read, :final? true, :value 200}]]
              :final-reads [100 200]
              :acceptable  [[200 200] [300 300]]
              :read-range  [[100 100] [200 200]]
              :bounds      [0 100]
              :possible    [[0 0] [100 100] [200 200] [300 300]]}
             (check-bounds [{:type :ok,   :f :increment, :value 100}
                            {:type :ok,   :f :read, :value 100}
                            {:type :info, :f :increment, :value 100}
                            {:type :ok,   :f :read, :value 100}
                            {:type :ok,   :f :increment, :value 100}
                            {:type :ok,   :f :read, :value 200}
                            {:type :ok,   :f :read, :final? true, :value 100}
                            {:type :ok,   :f :read, :final? true, :value 200}]))))))
