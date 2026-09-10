(ns growth.tenant-onboarding-test
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [growth.tenant-onboarding :as onboarding]))

(def valid-contract
  {:onboarding/type :revenue.tenant/onboarding
   :onboarding/version 1
   :tenant {:tenant/id :tenant/club-shinshi
            :tenant/legal-name "JK株式会社"
            :tenant/data-classification :data.classification/aggregate-only}
   :service {:service/operator-legal-name "Gftd Japan株式会社"
             :service/capabilities onboarding/allowed-capabilities
             :service/credential-mode :credential.mode/not-provisioned}
   :approvals [{:approval/role :human.role/tenant-owner
                :approval/actor-id "tenant-owner"
                :approval/status :approval.status/approved
                :approval/evidence-ref :evidence/tenant-owner}
               {:approval/role :human.role/itonami-operator
                :approval/actor-id "itonami-operator"
                :approval/status :approval.status/approved
                :approval/evidence-ref :evidence/itonami-operator}]
   :audit {:audit/owner-role :human.role/tenant-owner
           :audit/custodian-role :human.role/itonami-operator
           :audit/retention-days 2555}})

(deftest typed-contract-validates-offline
  (is (onboarding/valid? valid-contract))
  (is (= valid-contract (onboarding/validate! valid-contract)))
  (is (= onboarding/human-roles
         (set (map :approval/role (:approvals valid-contract))))))

(deftest validation-fails-closed
  (testing "closed shape and version"
    (is (some #{:onboarding/keys}
              (onboarding/validation-errors (assoc valid-contract :unknown true))))
    (is (some #{:onboarding/version}
              (onboarding/validation-errors (assoc valid-contract :onboarding/version 2)))))
  (testing "both distinct human roles are mandatory"
    (is (some #{:approvals/required-roles}
              (onboarding/validation-errors
               (assoc-in valid-contract [:approvals 1 :approval/role]
                         :human.role/tenant-owner))))
    (is (some #{:approvals/distinct-humans}
              (onboarding/validation-errors
               (assoc-in valid-contract [:approvals 1 :approval/actor-id]
                         "tenant-owner")))))
  (testing "approval evidence and audit ownership are mandatory"
    (is (some #{:approval/approved-without-evidence}
              (onboarding/validation-errors
               (assoc-in valid-contract [:approvals 0 :approval/evidence-ref] nil))))
    (is (some #{:audit/owner-role}
              (onboarding/validation-errors
               (assoc-in valid-contract [:audit :audit/owner-role]
                         :human.role/itonami-operator)))))
  (testing "credentials and expanded capabilities are rejected"
    (is (some #{:security/credential-material}
              (onboarding/validation-errors (assoc valid-contract :token "never"))))
    (is (some #{:service/capabilities}
              (onboarding/validation-errors
               (update-in valid-contract [:service :service/capabilities]
                          conj :revenue.capability/provision-credentials))))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (onboarding/validate!
                  (assoc-in valid-contract [:service :service/credential-mode]
                            :credential.mode/provisioned))))))

#?(:clj
   (deftest checked-in-edn-contract-validates
     (let [contract (edn/read-string
                     (slurp "test/fixtures/revenue_tenant_onboarding.edn"))]
       (is (onboarding/valid? contract))
       (is (= :human.role/tenant-owner
              (get-in contract [:audit :audit/owner-role]))))))
