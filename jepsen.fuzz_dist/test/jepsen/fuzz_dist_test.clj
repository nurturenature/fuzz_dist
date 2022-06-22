(ns jepsen.fuzz-dist-test
  (:require [clojure.test :refer :all]))

(deftest a-test
  (testing "This is a Jepsen test, and we are on the other side of the black box..."
    (is (< (rand-int 2) (rand-int 10)))))
