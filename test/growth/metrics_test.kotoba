(ns growth.metrics-test
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [growth.metrics :as metrics]))

(def valid-row
  {:metric-contract/version 1
   :window/start "2026-07-01T00:00:00Z"
   :window/end "2026-07-08T00:00:00Z"
   :window/timezone "UTC"
   :dimension/type :all
   :dimension/value "all"
   :activation/event-version :activation/completed-v1
   :retention/event-version :retention/meaningful-return-v1
   :retention/window-days 7
   :qualified-entrances 1000
   :activated-entrances 400
   :first-paid-orders 100
   :settled-gross-jpy-minor 1000000
   :refunds-jpy-minor 50000
   :chargebacks-jpy-minor 10000
   :tax-jpy-minor 80000
   :processor-fees-jpy-minor 30000
   :creator-payouts-jpy-minor 400000
   :eligible-cohort 200
   :retained-actors 50})

(deftest definitions-cover-the-five-contract-metrics
  (is (= #{:acquisition :activation :conversion :paid-net-revenue :retention}
         (set (keys metrics/metric-definitions))))
  (is (= :jpy-minor (get-in metrics/metric-definitions [:paid-net-revenue :unit])))
  (is (= :basis-points (get-in metrics/metric-definitions [:retention :unit]))))

(deftest deterministic-calculation
  (let [expected {:metric-contract/version 1
                  :window/start "2026-07-01T00:00:00Z"
                  :window/end "2026-07-08T00:00:00Z"
                  :dimension/type :all
                  :dimension/value "all"
                  :metrics
                  {:acquisition {:value 1000 :unit :count}
                   :activation {:value 4000 :unit :basis-points}
                   :conversion {:value 2500 :unit :basis-points}
                   :paid-net-revenue {:value 830000 :unit :jpy-minor
                                      :creator-payouts-jpy-minor 400000}
                   :retention {:value 2500 :unit :basis-points :window-days 7}}}]
    (is (= expected (metrics/calculate valid-row)))
    (is (= (metrics/calculate valid-row) (metrics/calculate valid-row)))))

(deftest ratio-rounding-and-zero-denominators-are-explicit
  (let [rounded (metrics/calculate
                 (assoc valid-row :qualified-entrances 3 :activated-entrances 2
                        :first-paid-orders 1 :eligible-cohort 3 :retained-actors 2))
        empty-funnel (metrics/calculate
                      (assoc valid-row :qualified-entrances 0 :activated-entrances 0
                             :first-paid-orders 0 :eligible-cohort 0 :retained-actors 0))]
    (is (= 6667 (get-in rounded [:metrics :activation :value])))
    (is (= 5000 (get-in rounded [:metrics :conversion :value])))
    (is (= 6667 (get-in rounded [:metrics :retention :value])))
    (is (nil? (get-in empty-funnel [:metrics :activation :value])))
    (is (nil? (get-in empty-funnel [:metrics :conversion :value])))
    (is (nil? (get-in empty-funnel [:metrics :retention :value])))))

(deftest validation-fails-closed
  (testing "unknown and missing fields"
    (is (some #{:row/keys} (metrics/validation-errors (assoc valid-row :unknown 1))))
    (is (some #{:row/keys} (metrics/validation-errors (dissoc valid-row :eligible-cohort)))))
  (testing "invalid counts and impossible funnel relationships"
    (is (some #{:value/natural-integer}
              (metrics/validation-errors (assoc valid-row :refunds-jpy-minor -1))))
    (is (some #{:activation/numerator-exceeds-denominator}
              (metrics/validation-errors (assoc valid-row :activated-entrances 1001))))
    (is (some #{:conversion/numerator-exceeds-denominator}
              (metrics/validation-errors (assoc valid-row :first-paid-orders 401))))
    (is (some #{:retention/numerator-exceeds-denominator}
              (metrics/validation-errors (assoc valid-row :retained-actors 201)))))
  (testing "window, version, dimension, and event contracts"
    (is (some #{:contract/version}
              (metrics/validation-errors (assoc valid-row :metric-contract/version 2))))
    (is (some #{:window/order}
              (metrics/validation-errors (assoc valid-row :window/end (:window/start valid-row)))))
    (is (some #{:window/timezone}
              (metrics/validation-errors (assoc valid-row :window/timezone "Asia/Tokyo"))))
    (is (some #{:dimension/type}
              (metrics/validation-errors (assoc valid-row :dimension/type :person))))
    (is (some #{:event/version}
              (metrics/validation-errors (assoc valid-row :activation/event-version "latest")))))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (metrics/validate! (assoc valid-row :email "person@example.test")))))

(deftest privacy-safe-aggregate-only-contract
  (is (some #{:row/keys}
            (metrics/validation-errors (assoc valid-row :user-id "u-1"))))
  (is (some #{:privacy/forbidden-key}
            (metrics/validation-errors
             (assoc valid-row :dimension/value {:campaign "safe" :email "x"})))))

#?(:clj
   (deftest checked-in-fixture-validates-and-calculates
     (let [fixture (edn/read-string (slurp "test/fixtures/growth_metrics.edn"))
           results (metrics/calculate-fixture fixture)]
       (is (= 1 (count results)))
       (is (= 830000 (get-in results [0 :metrics :paid-net-revenue :value])))
       (is (= 2500 (get-in results [0 :metrics :retention :value]))))))

(deftest fixture-envelope-is-typed-and-closed
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (metrics/calculate-fixture {:fixture/type :wrong
                                           :fixture/version 1
                                           :rows [valid-row]})))
  (is (= [(metrics/calculate valid-row)]
         (metrics/calculate-fixture
          {:fixture/type :growth.metric/aggregate-fixture
           :fixture/version 1
           :rows [valid-row]}))))
