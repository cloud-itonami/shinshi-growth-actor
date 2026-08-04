(ns growth.revenue-agent
  "Deterministic governed revenue-agent tick. Consumes an injected snapshot of
  read-only aggregate facts, verifies tenant capabilities and explicit human
  decisions, ranks only approved experiments, and returns append-only audit
  facts. It has no actuation or Store-writing dependency."
  (:require [growth.experiment-ranking :as ranking]
            [growth.facts :as facts]
            [growth.tenant-onboarding :as onboarding]))

(def agent-version 1)

(def required-capabilities
  #{:revenue.capability/read-aggregate-metrics
    :revenue.capability/draft-experiments
    :revenue.capability/request-approval})

(defn- audit [tick-id event data]
  (merge {:revenue.audit/version agent-version
          :revenue.audit/tick-id tick-id
          :revenue.audit/event event}
         data))

(defn- approved-onboarding? [contract]
  (and (onboarding/valid? contract)
       (every? #(= :approval.status/approved (:approval/status %))
               (:approvals contract))))

(defn- fact-errors [live-facts]
  (let [values (concat [(:revenue live-facts)]
                       (vals (or (:metrics live-facts) {})))]
    (vec (filter facts/error? values))))

(defn- decision-for [decisions experiment-id]
  (some #(when (= experiment-id (:experiment/id %)) %) decisions))

(defn- valid-decision? [decision]
  (and (map? decision)
       (contains? #{:approved :rejected} (:decision/status decision))
       (string? (:decision/by decision))
       (seq (:decision/by decision))
       (keyword? (:decision/evidence-ref decision))))

(defn tick
  "Runs one bounded, deterministic advisory tick. Input keys are :tick/id,
  :onboarding, :facts, :experiments, and :human-decisions. Returns a status,
  ranked approved experiments, and auditable events. Never executes, publishes,
  spends, prices, pays, or mutates any supplied value."
  [{tick-id :tick/id contract :onboarding live-facts :facts
    experiments :experiments decisions :human-decisions}]
  (let [capabilities (get-in contract [:service :service/capabilities])
        tenant-id (get-in contract [:tenant :tenant/id])
        expected-live-tenant (some-> tenant-id name)
        errors (fact-errors live-facts)
        boundary-error
        (cond
          (not (approved-onboarding? contract)) :onboarding-not-approved
          (not (every? capabilities required-capabilities)) :capability-boundary
          (not= expected-live-tenant (:growth.tenant/id live-facts)) :tenant-boundary
          (seq errors) :live-facts-unavailable
          :else nil)]
    (if boundary-error
      {:agent/version agent-version
       :tick/id tick-id
       :status :hold
       :ranked []
       :audit [(audit tick-id :tick-held
                      {:reason boundary-error
                       :fact-error-count (count errors)})]}
      (let [evaluated
            (mapv (fn [experiment]
                    (let [id (:experiment/id experiment)
                          decision (decision-for decisions id)
                          status (if (valid-decision? decision)
                                   (:decision/status decision)
                                   :hold)]
                      {:experiment (assoc experiment :governance/decision status)
                       :decision decision
                       :status status}))
                  experiments)
            ranked (ranking/rank (mapv :experiment evaluated))
            decision-audit
            (mapv (fn [{:keys [experiment decision status]}]
                    (audit tick-id :experiment-decided
                           (cond-> {:experiment/id (:experiment/id experiment)
                                    :decision/status status
                                    :rank-eligible? (= :approved status)}
                             (valid-decision? decision)
                             (assoc :decision/by (:decision/by decision)
                                    :decision/evidence-ref (:decision/evidence-ref decision)))))
                  evaluated)]
        {:agent/version agent-version
         :tick/id tick-id
         :status :advisory-only
         :ranked ranked
         :audit (into [(audit tick-id :facts-consumed
                             {:tenant/id tenant-id
                              :fact-source :live-read-only
                              :experiment-count (count experiments)})]
                      decision-audit)}))))
