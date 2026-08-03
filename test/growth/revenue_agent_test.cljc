(ns growth.revenue-agent-test
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [growth.revenue-agent :as agent]
            [growth.tenant-onboarding-test :as onboarding-test]))

(def base-tick
  {:tick/id :tick/test
   :onboarding onboarding-test/valid-contract
   :facts {:growth.tenant/id "club-shinshi"
           :revenue {:paid-net-jpy-minor 420000}
           :metrics {:conversion-bps 850}}
   :experiments [{:experiment/id :approved
                  :conversion-lift-bps 200 :reach 10000 :confidence-bps 7500
                  :value-per-conversion-jpy-minor 1000 :cost-jpy-minor 20000
                  :downside-risk-jpy-minor 10000}
                 {:experiment/id :unapproved
                  :conversion-lift-bps 900 :reach 10000 :confidence-bps 9000
                  :value-per-conversion-jpy-minor 1000 :cost-jpy-minor 0
                  :downside-risk-jpy-minor 0}]
   :human-decisions [{:experiment/id :approved :decision/status :approved
                      :decision/by "tenant-owner"
                      :decision/evidence-ref :evidence/approved}]})

(deftest approved-only-ranking-is-deterministic-and-advisory
  (let [before base-tick
        result (agent/tick base-tick)]
    (is (= :advisory-only (:status result)))
    (is (= [:approved] (mapv :experiment/id (:ranked result))))
    (is (= result (agent/tick base-tick)))
    (is (= before base-tick) "tick does not mutate or actuate input")
    (is (= [:facts-consumed :experiment-decided :experiment-decided]
           (mapv :revenue.audit/event (:audit result))))
    (is (= :hold (-> result :audit last :decision/status))
        "missing explicit approval fails closed even when its score would win")))

(deftest explicit-rejection-is-audited-and-not-ranked
  (let [input (update base-tick :human-decisions conj
                      {:experiment/id :unapproved :decision/status :rejected
                       :decision/by "tenant-owner"
                       :decision/evidence-ref :evidence/rejected})
        result (agent/tick input)]
    (is (= [:approved] (mapv :experiment/id (:ranked result))))
    (is (= :rejected (-> result :audit last :decision/status)))
    (is (= "tenant-owner" (-> result :audit last :decision/by)))))

(deftest tenant-and-capability-boundaries-fail-closed
  (testing "wrong tenant"
    (let [result (agent/tick (assoc-in base-tick [:facts :growth.tenant/id] "other"))]
      (is (= :hold (:status result)))
      (is (= :tenant-boundary (-> result :audit first :reason)))
      (is (empty? (:ranked result)))))
  (testing "missing capability"
    (let [input (update-in base-tick [:onboarding :service :service/capabilities]
                           disj :revenue.capability/request-approval)
          result (agent/tick input)]
      (is (= :hold (:status result)))
      (is (= :capability-boundary (-> result :audit first :reason)))))
  (testing "unapproved onboarding"
    (let [input (assoc-in base-tick [:onboarding :approvals 0 :approval/status]
                          :approval.status/pending)
          result (agent/tick input)]
      (is (= :onboarding-not-approved (-> result :audit first :reason))))))

(deftest live-fact-errors-stop-the-whole-tick
  (let [input (assoc-in base-tick [:facts :metrics :conversion-bps]
                        {:growth.fact/status :error :reason :transport :detail "offline"})
        result (agent/tick input)]
    (is (= :hold (:status result)))
    (is (= :live-facts-unavailable (-> result :audit first :reason)))
    (is (= 1 (-> result :audit first :fact-error-count)))
    (is (empty? (:ranked result)))))

#?(:clj
   (deftest checked-in-tick-fixture-runs-with-onboarding-fixture
     (let [tick (edn/read-string (slurp "test/fixtures/revenue_agent_tick.edn"))
           onboarding (edn/read-string
                       (slurp "test/fixtures/revenue_tenant_onboarding.edn"))
           result (agent/tick (assoc tick :onboarding onboarding))]
       (is (= :advisory-only (:status result)))
       (is (= [:checkout-copy] (mapv :experiment/id (:ranked result))))
       (is (every? :revenue.audit/tick-id (:audit result))))))
