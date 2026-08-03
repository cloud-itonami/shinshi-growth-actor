(ns growth.experiment-ranking
  "Deterministic, governed revenue-experiment ranking. All rates are integer basis points and all money is integer JPY minor units.")

(def ranking-version 1)

(def required-keys
  #{:experiment/id :governance/decision :conversion-lift-bps :reach
    :confidence-bps :value-per-conversion-jpy-minor :cost-jpy-minor
    :downside-risk-jpy-minor})

(defn- natural-int? [x]
  (and (integer? x) (not (neg? x))))

(defn- rate? [x]
  (and (natural-int? x) (<= x 10000)))

(defn validation-errors
  "Returns stable validation errors. Unknown or missing fields fail closed."
  [experiment]
  (if-not (map? experiment)
    [:experiment/not-map]
    (cond-> []
      (not= required-keys (set (keys experiment)))
      (conj :experiment/keys)

      (not (or (keyword? (:experiment/id experiment))
               (string? (:experiment/id experiment))))
      (conj :experiment/id)

      (not (contains? #{:approved :hold :rejected}
                      (:governance/decision experiment)))
      (conj :governance/decision)

      (not (integer? (:conversion-lift-bps experiment)))
      (conj :conversion-lift-bps)

      (not (natural-int? (:reach experiment)))
      (conj :reach)

      (not (rate? (:confidence-bps experiment)))
      (conj :confidence-bps)

      (not (natural-int? (:value-per-conversion-jpy-minor experiment)))
      (conj :value-per-conversion-jpy-minor)

      (not (natural-int? (:cost-jpy-minor experiment)))
      (conj :cost-jpy-minor)

      (not (natural-int? (:downside-risk-jpy-minor experiment)))
      (conj :downside-risk-jpy-minor))))

(defn validate! [experiment]
  (let [errors (validation-errors experiment)]
    (when (seq errors)
      (throw (ex-info "invalid revenue experiment" {:errors errors})))
    experiment))

(defn- round-ratio
  "Rounds a signed integer numerator divided by a positive denominator half away from zero."
  [numerator denominator]
  (let [magnitude (quot (+ (abs numerator) (quot denominator 2)) denominator)]
    (if (neg? numerator) (- magnitude) magnitude)))

(defn score
  "Returns an auditable score and conservative uncertainty interval. Only an explicitly approved experiment is rank-eligible."
  [experiment]
  (validate! experiment)
  (let [projected-lift-value
        (round-ratio (* (:conversion-lift-bps experiment)
                        (:reach experiment)
                        (:value-per-conversion-jpy-minor experiment))
                     10000)
        expected-lift-value
        (round-ratio (* projected-lift-value (:confidence-bps experiment)) 10000)
        uncertainty
        (round-ratio (* (abs projected-lift-value)
                        (- 10000 (:confidence-bps experiment)))
                     10000)
        expected-value (- expected-lift-value
                          (:cost-jpy-minor experiment)
                          (:downside-risk-jpy-minor experiment))]
    {:ranking/version ranking-version
     :experiment/id (:experiment/id experiment)
     :rank-eligible? (= :approved (:governance/decision experiment))
     :governance/decision (:governance/decision experiment)
     :score/expected-value-jpy-minor expected-value
     :score/lower-bound-jpy-minor (- expected-value uncertainty
                                     (:downside-risk-jpy-minor experiment))
     :score/upper-bound-jpy-minor (+ expected-value uncertainty)
     :score/components
     {:projected-lift-value-jpy-minor projected-lift-value
      :confidence-adjusted-lift-value-jpy-minor expected-lift-value
      :cost-jpy-minor (:cost-jpy-minor experiment)
      :downside-risk-jpy-minor (:downside-risk-jpy-minor experiment)
      :uncertainty-jpy-minor uncertainty}}))

(defn rank
  "Scores all inputs, excludes non-approved experiments, and sorts by EV descending, lower bound descending, then printed id ascending."
  [experiments]
  (->> experiments
       (map score)
       (filter :rank-eligible?)
       (sort-by (juxt (comp - :score/expected-value-jpy-minor)
                      (comp - :score/lower-bound-jpy-minor)
                      (comp pr-str :experiment/id)))
       vec))
