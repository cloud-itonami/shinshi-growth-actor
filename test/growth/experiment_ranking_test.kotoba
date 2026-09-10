(ns growth.experiment-ranking-test
  (:require [clojure.test :refer [deftest is testing]]
            [growth.experiment-ranking :as ranking]))

(def base-experiment
  {:experiment/id :checkout-copy
   :governance/decision :approved
   :conversion-lift-bps 200
   :reach 10000
   :confidence-bps 7500
   :value-per-conversion-jpy-minor 1000
   :cost-jpy-minor 20000
   :downside-risk-jpy-minor 10000})

(deftest deterministic-transparent-score
  (let [expected {:ranking/version 1
                  :experiment/id :checkout-copy
                  :rank-eligible? true
                  :governance/decision :approved
                  :score/expected-value-jpy-minor 120000
                  :score/lower-bound-jpy-minor 60000
                  :score/upper-bound-jpy-minor 170000
                  :score/components
                  {:projected-lift-value-jpy-minor 200000
                   :confidence-adjusted-lift-value-jpy-minor 150000
                   :cost-jpy-minor 20000
                   :downside-risk-jpy-minor 10000
                   :uncertainty-jpy-minor 50000}}]
    (is (= expected (ranking/score base-experiment)))
    (is (= (ranking/score base-experiment)
           (ranking/score base-experiment)))))

(deftest ranking-is-governed-and-stable
  (let [higher (assoc base-experiment :experiment/id :higher
                      :conversion-lift-bps 300)
        tied-a (assoc base-experiment :experiment/id :a)
        tied-b (assoc base-experiment :experiment/id :b)
        held (assoc higher :experiment/id :held
                    :governance/decision :hold)]
    (is (= [:higher :a :b]
           (mapv :experiment/id (ranking/rank [tied-b held tied-a higher]))))
    (is (false? (:rank-eligible? (ranking/score held))))))

(deftest confidence-cost-and-risk-affect-score-explicitly
  (let [low-confidence (ranking/score (assoc base-experiment :confidence-bps 2500))
        high-cost (ranking/score (assoc base-experiment :cost-jpy-minor 120000))
        high-risk (ranking/score (assoc base-experiment :downside-risk-jpy-minor 110000))]
    (is (= 20000 (:score/expected-value-jpy-minor low-confidence)))
    (is (= 150000 (get-in low-confidence [:score/components :uncertainty-jpy-minor])))
    (is (= 20000 (:score/expected-value-jpy-minor high-cost)))
    (is (= 20000 (:score/expected-value-jpy-minor high-risk)))
    (is (< (:score/lower-bound-jpy-minor high-risk)
           (:score/lower-bound-jpy-minor high-cost)))))

(deftest signed-lift-and-rounding-are-deterministic
  (let [negative (ranking/score
                  (assoc base-experiment
                         :conversion-lift-bps -1
                         :reach 1
                         :value-per-conversion-jpy-minor 5000
                         :confidence-bps 10000
                         :cost-jpy-minor 0
                         :downside-risk-jpy-minor 0))]
    (is (= -1 (get-in negative [:score/components :projected-lift-value-jpy-minor])))
    (is (= -1 (:score/expected-value-jpy-minor negative)))))

(deftest validation-fails-closed
  (testing "unknown and missing fields"
    (is (some #{:experiment/keys}
              (ranking/validation-errors (assoc base-experiment :unknown true))))
    (is (some #{:experiment/keys}
              (ranking/validation-errors (dissoc base-experiment :reach)))))
  (testing "invalid rates, money, and governance"
    (is (some #{:confidence-bps}
              (ranking/validation-errors (assoc base-experiment :confidence-bps 10001))))
    (is (some #{:cost-jpy-minor}
              (ranking/validation-errors (assoc base-experiment :cost-jpy-minor -1))))
    (is (some #{:governance/decision}
              (ranking/validation-errors (assoc base-experiment :governance/decision :unknown)))))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (ranking/score (dissoc base-experiment :reach)))))
