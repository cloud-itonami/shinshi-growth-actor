(ns growth.revenue-pilot-test
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [growth.revenue-pilot :as pilot]))

(def approved-plan
  {:pilot/id :pilot/test
   :pilot/cohort {:dimension/type :campaign :dimension/value "test"}
   :pilot/baseline-window {:window/start "2026-01-01T00:00:00Z"
                           :window/end "2026-01-08T00:00:00Z"
                           :window/timezone "UTC"}
   :pilot/measurement-window {:window/start "2026-01-08T00:00:00Z"
                              :window/end "2026-01-15T00:00:00Z"
                              :window/timezone "UTC"}
   :pilot/metric :revenue/paid-net
   :pilot/baseline-jpy-minor 100000
   :pilot/target-uplift-bps 500
   :pilot/minimum-cohort-size 100
   :pilot/stop-conditions {:minimum-paid-net-revenue-jpy-minor 90000
                           :maximum-refund-rate-bps 1000}
   :pilot/approval {:approval/status :approved :approval/by "owner"
                    :approval/evidence-ref :evidence/test}
   :pilot/prohibited-actions #{:payments :ad-spend :publishing :external-writes}})

(def observation
  {:pilot/id :pilot/test
   :pilot/cohort {:dimension/type :campaign :dimension/value "test"}
   :window/start "2026-01-08T00:00:00Z"
   :window/end "2026-01-15T00:00:00Z"
   :cohort-size 120
   :paid-net-revenue-jpy-minor 106000
   :refunds-jpy-minor 5000
   :settled-gross-jpy-minor 120000})

(deftest measures-uplift-deterministically-without-actions
  (let [result (pilot/evaluate approved-plan observation)]
    (is (= result (pilot/evaluate approved-plan observation)))
    (is (= :target-met (:result/status result)))
    (is (= 6000 (:result/uplift-jpy-minor result)))
    (is (= 600 (:result/uplift-bps result)))
    (is (= [] (:result/actions result)))
    (is (true? (:audit/non-actuating? result)))))

(deftest approval-and-boundaries-fail-closed
  (testing "missing human approval holds evaluation"
    (let [result (pilot/evaluate
                  (assoc-in approved-plan [:pilot/approval :approval/status] :pending)
                  observation)]
      (is (= :hold (:result/status result)))
      (is (some #{:pilot/human-approval} (:result/reasons result)))))
  (testing "a changed cohort holds evaluation"
    (let [result (pilot/evaluate approved-plan
                                 (assoc observation :pilot/cohort {:other true}))]
      (is (= :hold (:result/status result)))
      (is (some #{:pilot/cohort-mismatch} (:result/reasons result)))))
  (testing "removing any non-actuation boundary holds evaluation"
    (let [result (pilot/evaluate
                  (update approved-plan :pilot/prohibited-actions disj :external-writes)
                  observation)]
      (is (= :hold (:result/status result))))))

(deftest explicit-stop-conditions-stop-the-pilot
  (let [result (pilot/evaluate approved-plan
                               (assoc observation
                                      :cohort-size 99
                                      :paid-net-revenue-jpy-minor 89000
                                      :refunds-jpy-minor 13000))]
    (is (= :stop (:result/status result)))
    (is (= [:minimum-cohort-size :minimum-paid-net-revenue :maximum-refund-rate]
           (:result/reasons result)))
    (is (empty? (:result/actions result)))))

#?(:clj
   (deftest checked-in-fixture-is-golden
     (let [{:keys [plan observation]}
           (edn/read-string (slurp "test/fixtures/revenue_pilot.edn"))
           result (pilot/evaluate plan observation)]
       (is (= :target-met (:result/status result)))
       (is (= 600 (:result/uplift-bps result)))
       (is (= :approval.evidence/checkout-pilot-v1
              (get-in result [:audit/approval :approval/evidence-ref]))))))
