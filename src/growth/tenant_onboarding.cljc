(ns growth.tenant-onboarding
  "Closed, offline EDN contract for revenue-tenant onboarding and approval.
  Validation only: no credentials, writes, clock reads, or network calls.")

(def contract-version 1)

(def human-roles
  #{:human.role/itonami-operator :human.role/tenant-owner})

(def allowed-capabilities
  #{:revenue.capability/read-aggregate-metrics
    :revenue.capability/draft-experiments
    :revenue.capability/request-approval})

(def approval-statuses
  #{:approval.status/pending :approval.status/approved :approval.status/rejected})

(def required-keys
  #{:onboarding/type :onboarding/version :tenant :service :approvals :audit})
(def tenant-keys
  #{:tenant/id :tenant/legal-name :tenant/data-classification})
(def service-keys
  #{:service/operator-legal-name :service/capabilities :service/credential-mode})
(def approval-keys
  #{:approval/role :approval/actor-id :approval/status :approval/evidence-ref})
(def audit-keys
  #{:audit/owner-role :audit/custodian-role :audit/retention-days})

(def forbidden-keys
  #{:credential :credentials :password :secret :token :api-key :private-key
    :access-key :client-secret})

(defn- closed-map? [ks x]
  (and (map? x) (= ks (set (keys x)))))

(defn- nonblank-string? [x]
  (and (string? x) (seq x)))

(defn- walk-keys [x]
  (cond
    (map? x) (concat (keys x) (mapcat walk-keys (vals x)))
    (sequential? x) (mapcat walk-keys x)
    (set? x) (mapcat walk-keys x)
    :else []))

(defn- approval-errors [approval]
  (cond-> []
    (not (closed-map? approval-keys approval)) (conj :approval/keys)
    (not (contains? human-roles (:approval/role approval))) (conj :approval/role)
    (not (nonblank-string? (:approval/actor-id approval))) (conj :approval/actor-id)
    (not (contains? approval-statuses (:approval/status approval))) (conj :approval/status)
    (not (or (nil? (:approval/evidence-ref approval))
             (keyword? (:approval/evidence-ref approval))))
    (conj :approval/evidence-ref)
    (and (= :approval.status/approved (:approval/status approval))
         (nil? (:approval/evidence-ref approval)))
    (conj :approval/approved-without-evidence)))

(defn validation-errors
  "Returns stable error keywords; empty means the closed contract is valid."
  [contract]
  (if-not (map? contract)
    [:onboarding/not-map]
    (let [tenant (:tenant contract)
          service (:service contract)
          approvals (:approvals contract)
          audit (:audit contract)
          errors (cond-> []
                   (not= required-keys (set (keys contract))) (conj :onboarding/keys)
                   (not= :revenue.tenant/onboarding (:onboarding/type contract))
                   (conj :onboarding/type)
                   (not= contract-version (:onboarding/version contract))
                   (conj :onboarding/version)
                   (not (closed-map? tenant-keys tenant)) (conj :tenant/keys)
                   (not (keyword? (:tenant/id tenant))) (conj :tenant/id)
                   (not (nonblank-string? (:tenant/legal-name tenant)))
                   (conj :tenant/legal-name)
                   (not= :data.classification/aggregate-only
                         (:tenant/data-classification tenant))
                   (conj :tenant/data-classification)
                   (not (closed-map? service-keys service)) (conj :service/keys)
                   (not (nonblank-string? (:service/operator-legal-name service)))
                   (conj :service/operator-legal-name)
                   (not (and (set? (:service/capabilities service))
                             (every? allowed-capabilities (:service/capabilities service))))
                   (conj :service/capabilities)
                   (not= :credential.mode/not-provisioned
                         (:service/credential-mode service))
                   (conj :service/credential-mode)
                   (not (and (vector? approvals) (= 2 (count approvals))))
                   (conj :approvals/shape)
                   (not= human-roles (set (map :approval/role approvals)))
                   (conj :approvals/required-roles)
                   (not= (count approvals)
                         (count (set (map :approval/actor-id approvals))))
                   (conj :approvals/distinct-humans)
                   (not (closed-map? audit-keys audit)) (conj :audit/keys)
                   (not= :human.role/tenant-owner (:audit/owner-role audit))
                   (conj :audit/owner-role)
                   (not= :human.role/itonami-operator (:audit/custodian-role audit))
                   (conj :audit/custodian-role)
                   (not (pos-int? (:audit/retention-days audit)))
                   (conj :audit/retention-days)
                   (some forbidden-keys (walk-keys contract))
                   (conj :security/credential-material))]
      (vec (distinct
            (concat errors
                    (mapcat approval-errors
                            (if (vector? approvals) approvals []))))))))

(defn valid? [contract]
  (empty? (validation-errors contract)))

(defn validate! [contract]
  (let [errors (validation-errors contract)]
    (when (seq errors)
      (throw (ex-info "invalid revenue tenant onboarding contract"
                      {:errors errors})))
    contract))
