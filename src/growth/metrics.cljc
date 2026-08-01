(ns growth.metrics
  "Deterministic, privacy-safe aggregate growth metric contract. Ratios use integer basis points; no network, clock, secrets, direct identifiers, or free text are accepted.")

(def contract-version 1)

(def dimensions #{:all :channel :campaign :country :device})

(def forbidden-keys
  #{:email :phone :name :address :ip :ip-address :user-id :account-id
    :session-id :device-id :cookie :token :secret :free-text})

(def metric-definitions
  {:acquisition
   {:numerator :qualified-entrances :unit :count
    :definition "Deduplicated anonymous entrances passing bot and consent filters in the window."}
   :activation
   {:numerator :activated-entrances :denominator :qualified-entrances
    :unit :basis-points
    :definition "Qualified entrances completing the versioned activation event in the same window."}
   :conversion
   {:numerator :first-paid-orders :denominator :activated-entrances
    :unit :basis-points
    :definition "Activated entrances with a first settled paid order in the attribution window."}
   :paid-net-revenue
   {:unit :jpy-minor
    :formula [:- :settled-gross-jpy-minor :refunds-jpy-minor
              :chargebacks-jpy-minor :tax-jpy-minor :processor-fees-jpy-minor]
    :definition "Settled paid gross less refunds, chargebacks, tax, and processor fees; creator payouts remain separately reported."}
   :retention
   {:numerator :retained-actors :denominator :eligible-cohort
    :unit :basis-points
    :definition "Members of a closed acquisition cohort completing the versioned retained event in the exact retention window."}})

(def required-keys
  #{:metric-contract/version :window/start :window/end :window/timezone
    :dimension/type :dimension/value :activation/event-version
    :retention/event-version :retention/window-days :qualified-entrances
    :activated-entrances :first-paid-orders :settled-gross-jpy-minor
    :refunds-jpy-minor :chargebacks-jpy-minor :tax-jpy-minor
    :processor-fees-jpy-minor :creator-payouts-jpy-minor
    :eligible-cohort :retained-actors})

(defn- natural-int? [x]
  (and (integer? x) (not (neg? x))))

(defn- walk-keys [x]
  (cond
    (map? x) (concat (keys x) (mapcat walk-keys (vals x)))
    (sequential? x) (mapcat walk-keys x)
    (set? x) (mapcat walk-keys x)
    :else []))

(defn validation-errors
  "Returns a stable vector of error keywords. Empty means the aggregate row conforms. Unknown and missing fields fail closed."
  [row]
  (if-not (map? row)
    [:row/not-map]
    (let [count-keys [:qualified-entrances :activated-entrances :first-paid-orders
                      :settled-gross-jpy-minor :refunds-jpy-minor
                      :chargebacks-jpy-minor :tax-jpy-minor
                      :processor-fees-jpy-minor :creator-payouts-jpy-minor
                      :eligible-cohort :retained-actors]
          errors (cond-> []
                   (not= required-keys (set (keys row))) (conj :row/keys)
                   (not= contract-version (:metric-contract/version row))
                   (conj :contract/version)
                   (not (contains? dimensions (:dimension/type row)))
                   (conj :dimension/type)
                   (not (string? (:dimension/value row)))
                   (conj :dimension/value)
                   (not (every? #(natural-int? (get row %)) count-keys))
                   (conj :value/natural-integer)
                   (not (pos-int? (:retention/window-days row)))
                   (conj :retention/window-days)
                   (not (and (string? (:window/start row))
                             (string? (:window/end row))
                             (neg? (compare (:window/start row) (:window/end row)))))
                   (conj :window/order)
                   (not= "UTC" (:window/timezone row))
                   (conj :window/timezone)
                   (or (not (keyword? (:activation/event-version row)))
                       (not (keyword? (:retention/event-version row))))
                   (conj :event/version)
                   (> (or (:activated-entrances row) 0)
                      (or (:qualified-entrances row) 0))
                   (conj :activation/numerator-exceeds-denominator)
                   (> (or (:first-paid-orders row) 0)
                      (or (:activated-entrances row) 0))
                   (conj :conversion/numerator-exceeds-denominator)
                   (> (or (:retained-actors row) 0)
                      (or (:eligible-cohort row) 0))
                   (conj :retention/numerator-exceeds-denominator)
                   (some forbidden-keys (walk-keys row))
                   (conj :privacy/forbidden-key))]
      (vec (distinct errors)))))

(defn valid? [row]
  (empty? (validation-errors row)))

(defn validate! [row]
  (let [errors (validation-errors row)]
    (when (seq errors)
      (throw (ex-info "invalid growth metric aggregate" {:errors errors})))
    row))

(defn- basis-points [numerator denominator]
  (when (pos? denominator)
    (quot (+ (* numerator 10000) (quot denominator 2)) denominator)))

(defn calculate
  "Validates an aggregate row and returns the canonical five metrics. A zero denominator yields nil, never a fabricated zero."
  [row]
  (validate! row)
  {:metric-contract/version contract-version
   :window/start (:window/start row)
   :window/end (:window/end row)
   :dimension/type (:dimension/type row)
   :dimension/value (:dimension/value row)
   :metrics
   {:acquisition {:value (:qualified-entrances row) :unit :count}
    :activation {:value (basis-points (:activated-entrances row)
                                     (:qualified-entrances row))
                 :unit :basis-points}
    :conversion {:value (basis-points (:first-paid-orders row)
                                     (:activated-entrances row))
                 :unit :basis-points}
    :paid-net-revenue
    {:value (- (:settled-gross-jpy-minor row)
               (:refunds-jpy-minor row)
               (:chargebacks-jpy-minor row)
               (:tax-jpy-minor row)
               (:processor-fees-jpy-minor row))
     :unit :jpy-minor
     :creator-payouts-jpy-minor (:creator-payouts-jpy-minor row)}
    :retention {:value (basis-points (:retained-actors row)
                                    (:eligible-cohort row))
                :unit :basis-points
                :window-days (:retention/window-days row)}}})

(defn calculate-fixture
  "Validates a typed fixture envelope and calculates its rows in input order."
  [fixture]
  (when-not (and (map? fixture)
                 (= #{:fixture/type :fixture/version :rows} (set (keys fixture)))
                 (= :growth.metric/aggregate-fixture (:fixture/type fixture))
                 (= 1 (:fixture/version fixture))
                 (vector? (:rows fixture)))
    (throw (ex-info "invalid metric fixture envelope" {:error :fixture/envelope})))
  (mapv calculate (:rows fixture)))
