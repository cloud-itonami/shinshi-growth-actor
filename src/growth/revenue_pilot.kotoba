(ns growth.revenue-pilot
  "Deterministic, non-actuating evaluator for an approved revenue pilot.")

(def evaluator-version 1)

(def plan-keys
  #{:pilot/id :pilot/cohort :pilot/baseline-window :pilot/measurement-window
    :pilot/metric :pilot/baseline-jpy-minor :pilot/target-uplift-bps
    :pilot/minimum-cohort-size :pilot/stop-conditions :pilot/approval
    :pilot/prohibited-actions})

(def observation-keys
  #{:pilot/id :pilot/cohort :window/start :window/end :cohort-size
    :paid-net-revenue-jpy-minor :refunds-jpy-minor :settled-gross-jpy-minor})

(def required-prohibitions
  #{:payments :ad-spend :publishing :external-writes})

(defn- natural-int? [x]
  (and (integer? x) (not (neg? x))))

(defn- positive-int? [x]
  (and (integer? x) (pos? x)))

(defn- window? [window]
  (and (= #{:window/start :window/end :window/timezone} (set (keys window)))
       (string? (:window/start window))
       (string? (:window/end window))
       (neg? (compare (:window/start window) (:window/end window)))
       (= "UTC" (:window/timezone window))))

(defn- approval? [approval]
  (and (= #{:approval/status :approval/by :approval/evidence-ref}
          (set (keys approval)))
       (= :approved (:approval/status approval))
       (string? (:approval/by approval))
       (seq (:approval/by approval))
       (keyword? (:approval/evidence-ref approval))))

(defn validation-errors [plan observation]
  (vec
   (concat
    (when-not (and (map? plan) (= plan-keys (set (keys plan))))
      [:pilot/plan-schema])
    (when-not (and (map? observation)
                   (= observation-keys (set (keys observation))))
      [:pilot/observation-schema])
    (when-not (and (window? (:pilot/baseline-window plan))
                   (window? (:pilot/measurement-window plan)))
      [:pilot/window])
    (when-not (= (:window/end (:pilot/baseline-window plan))
                 (:window/start (:pilot/measurement-window plan)))
      [:pilot/window-continuity])
    (when-not (= :revenue/paid-net (:pilot/metric plan))
      [:pilot/metric])
    (when-not (and (natural-int? (:pilot/baseline-jpy-minor plan))
                   (positive-int? (:pilot/minimum-cohort-size plan))
                   (natural-int? (:pilot/target-uplift-bps plan)))
      [:pilot/thresholds])
    (when-not (= #{:minimum-paid-net-revenue-jpy-minor :maximum-refund-rate-bps}
                 (set (keys (:pilot/stop-conditions plan))))
      [:pilot/stop-schema])
    (when-not (every? natural-int? (vals (:pilot/stop-conditions plan)))
      [:pilot/stop-values])
    (when-not (approval? (:pilot/approval plan))
      [:pilot/human-approval])
    (when-not (= required-prohibitions (:pilot/prohibited-actions plan))
      [:pilot/non-actuation-boundary])
    (when-not (= (:pilot/id plan) (:pilot/id observation))
      [:pilot/id-mismatch])
    (when-not (= (:pilot/cohort plan) (:pilot/cohort observation))
      [:pilot/cohort-mismatch])
    (when-not (= [(:window/start (:pilot/measurement-window plan))
                  (:window/end (:pilot/measurement-window plan))]
                 [(:window/start observation) (:window/end observation)])
      [:pilot/observation-window])
    (when-not (every? natural-int?
                      ((juxt :cohort-size :paid-net-revenue-jpy-minor
                             :refunds-jpy-minor :settled-gross-jpy-minor)
                       observation))
      [:pilot/observation-values]))))

(defn- ratio-bps [numerator denominator]
  (when (pos? denominator)
    (quot (+ (* numerator 10000) (quot denominator 2)) denominator)))

(defn evaluate
  "Returns a closed audit/result record. It performs no I/O or actuation."
  [plan observation]
  (let [errors (validation-errors plan observation)]
    (if (seq errors)
      {:revenue.pilot/version evaluator-version
       :pilot/id (:pilot/id plan)
       :result/status :hold
       :result/reasons errors
       :result/actions []
       :audit/approval (:pilot/approval plan)}
      (let [baseline (:pilot/baseline-jpy-minor plan)
            measured (:paid-net-revenue-jpy-minor observation)
            uplift (- measured baseline)
            uplift-bps (ratio-bps uplift baseline)
            refund-bps (ratio-bps (:refunds-jpy-minor observation)
                                  (:settled-gross-jpy-minor observation))
            stops (cond-> []
                    (< (:cohort-size observation) (:pilot/minimum-cohort-size plan))
                    (conj :minimum-cohort-size)
                    (< measured (get-in plan [:pilot/stop-conditions
                                             :minimum-paid-net-revenue-jpy-minor]))
                    (conj :minimum-paid-net-revenue)
                    (or (nil? refund-bps)
                        (> refund-bps
                           (get-in plan [:pilot/stop-conditions
                                        :maximum-refund-rate-bps])))
                    (conj :maximum-refund-rate))
            status (cond
                     (seq stops) :stop
                     (>= (or uplift-bps 0) (:pilot/target-uplift-bps plan)) :target-met
                     :else :target-not-met)]
        {:revenue.pilot/version evaluator-version
         :pilot/id (:pilot/id plan)
         :pilot/cohort (:pilot/cohort plan)
         :pilot/measurement-window (:pilot/measurement-window plan)
         :result/status status
         :result/reasons stops
         :result/baseline-jpy-minor baseline
         :result/measured-jpy-minor measured
         :result/uplift-jpy-minor uplift
         :result/uplift-bps uplift-bps
         :result/refund-rate-bps refund-bps
         :result/cohort-size (:cohort-size observation)
         :result/target-uplift-bps (:pilot/target-uplift-bps plan)
         :result/actions []
         :audit/approval (:pilot/approval plan)
         :audit/non-actuating? true}))))
