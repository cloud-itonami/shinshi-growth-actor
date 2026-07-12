(ns growth.facts-test
  "Contract tests for `growth.facts/live-facts` — the Phase 1 read-only
  live-metrics fetch. Per this repo's I/O-injection convention
  (`langchain.model`/`langchain.kotoba-db`), `:http-fn`/`:json-write`/
  `:json-read` are always fakes here: NO test in this namespace touches the
  network. `:json-write`/`:json-read` are `pr-str`/`clojure.edn/read-string`
  (mirroring `langchain`'s own kotoba-db test convention) so the fake
  `http-fn` can pattern-match on EDN request bodies without a real JSON lib
  dependency.

  Covers the three required Phase 1 behaviors:
    - a fully successful fetch assembles the complete facts map, including
      honest-null passthrough for an upstream `nil` value.
    - an upstream `{:ok false}` response surfaces as an explicit
      `growth.facts/error?` marker for just that field — not a crash, not a
      fabricated number, and every other field is unaffected.
    - a raw transport failure (http-fn throws) is caught and surfaced the
      same way, for just that field."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [growth.facts :as facts]))

(def ^:private secret "test-secret-value")
(def ^:private base-url "https://shinshi.club")

(def ^:private full-revenue
  {:creator-gmv-jpy 50000 :creator-net-jpy 45000 :ad-revenue-jpy 120000
   :ad-revenue-usd-minor 80000 :usd-jpy-rate 150.0 :window-days 7
   :as-of "2026-07-12T00:00:00Z"})

(def ^:private metric-values
  ;; adult_fill_pct deliberately nil — an honest upstream null, not a failure.
  {"organic_pageviews" 12345
   "organic_pv_growth_pct" 4.2
   "adult_fill_pct" nil
   "general_fill_pct" 61.5
   "firehose_referral_pct" 18.0
   "d7_return_pct" 33.3})

(defn- happy-http-fn
  "Every call succeeds: revenue GET → `full-revenue`; every read_metric POST
  → its value from `metric-values` (including the honest nil)."
  [{:keys [url method headers body]}]
  (is (= secret (get headers "x-internal-trust")) "auth header always carries the secret")
  (cond
    (and (= :get method) (str/starts-with? url (str base-url "/_metrics/revenue")))
    {:status 200 :body (pr-str {:ok true :revenue full-revenue})}

    (and (= :post method) (= (str base-url "/_d1") url))
    (let [{:keys [args]} (edn/read-string body)
          metric-name (:metric args)]
      {:status 200 :body (pr-str {:ok true :value (get metric-values metric-name ::missing)})})

    :else
    (throw (ex-info "unexpected request in fake http-fn" {:url url :method method}))))

(def ^:private io
  {:http-fn happy-http-fn :json-write pr-str :json-read edn/read-string})

(deftest live-facts-successful-fetch-assembles-full-map
  (testing "every field fetched; honest upstream nil passes through, not an error"
    (let [f (facts/live-facts io base-url secret)]
      (is (= "club-shinshi" (:growth.tenant/id f)))
      (is (= full-revenue (:revenue f)))
      (is (not (facts/error? (:revenue f))))
      (is (= {:organic-pageviews 12345
              :organic-pv-growth-pct 4.2
              :adult-fill-pct nil
              :general-fill-pct 61.5
              :firehose-referral-pct 18.0
              :d7-return-pct 33.3}
             (:metrics f)))
      (is (nil? (get-in f [:metrics :adult-fill-pct]))
          "an upstream null is a legitimate value, not an :error marker")
      (is (every? #(not (facts/error? %)) (vals (:metrics f)))))))

(deftest live-facts-upstream-ok-false-surfaces-error-marker-not-crash
  (testing "an {ok: false} response for one metric becomes an :error marker, others unaffected"
    (let [flaky-http-fn
          (fn [{:keys [url method body] :as req}]
            (if (and (= :post method) (= (str base-url "/_d1") url)
                     (= "adult_fill_pct" (:metric (:args (edn/read-string body)))))
              {:status 200 :body (pr-str {:ok false :error "metric temporarily disabled"})}
              (happy-http-fn req)))
          f (facts/live-facts {:http-fn flaky-http-fn :json-write pr-str :json-read edn/read-string}
                               base-url secret)]
      (is (facts/error? (get-in f [:metrics :adult-fill-pct]))
          "ok=false surfaces as an explicit error marker, not a crash")
      (is (= :upstream (:reason (get-in f [:metrics :adult-fill-pct]))))
      (is (not (facts/error? (get-in f [:metrics :organic-pageviews])))
          "an unrelated field's failure does not contaminate other fields")
      (is (= 12345 (get-in f [:metrics :organic-pageviews])))
      (is (not (facts/error? (:revenue f))) "revenue endpoint was untouched by this failure"))))

(deftest live-facts-transport-failure-caught-and-surfaced
  (testing "http-fn throwing (network/transport failure) is caught, not propagated"
    (let [throwing-http-fn
          (fn [{:keys [url method] :as req}]
            (if (and (= :get method) (str/starts-with? url (str base-url "/_metrics/revenue")))
              (throw (ex-info "connection reset" {:type :transport}))
              (happy-http-fn req)))
          f (facts/live-facts {:http-fn throwing-http-fn :json-write pr-str :json-read edn/read-string}
                               base-url secret)]
      (is (facts/error? (:revenue f)) "a thrown exception is caught and surfaced as an :error marker")
      (is (= :transport (:reason (:revenue f))))
      (is (every? #(not (facts/error? %)) (vals (:metrics f)))
          "metrics fetches are unaffected by the revenue endpoint's transport failure")
      (is (= 12345 (get-in f [:metrics :organic-pageviews]))))))

(deftest live-facts-requires-injected-io
  (testing "missing http-fn/json-write/json-read fails loudly at the call boundary, not silently"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (facts/live-facts {} base-url secret)))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (facts/live-facts {:http-fn happy-http-fn} base-url secret)))))

;; ───────────────────────── facts->store-metrics (Phase 2 adapter) ─────────────────────────

(deftest facts->store-metrics-keeps-every-non-error-field-under-its-own-key
  (testing "revenue + metrics fields all survive, each under their own live key name"
    (let [f  (facts/live-facts io base-url secret)
          sm (facts/facts->store-metrics f)]
      (is (= 50000 (:creator-gmv-jpy sm)))
      (is (= 120000 (:ad-revenue-jpy sm)))
      (is (= 12345 (:organic-pageviews sm)))
      (is (= 4.2 (:organic-pv-growth-pct sm)))
      (is (= 61.5 (:general-fill-pct sm)))
      (is (contains? sm :adult-fill-pct))
      (is (nil? (:adult-fill-pct sm)) "honest upstream null passes through the adapter too"))))

(deftest facts->store-metrics-aliases-organic-pv-growth-pct-to-demo-datas-own-key
  (testing "an existing :metric-reads [:organic-pageviews-mom-pct] request (growth.sim's op1) still resolves"
    (let [f  (facts/live-facts io base-url secret)
          sm (facts/facts->store-metrics f)]
      (is (= 4.2 (:organic-pageviews-mom-pct sm))
          "aliased from :organic-pv-growth-pct, demo-data's own name for the same H1-gate metric")
      (is (= (:organic-pv-growth-pct sm) (:organic-pageviews-mom-pct sm))
          "both the live name and the demo-data-compatible alias are present"))))

(deftest facts->store-metrics-drops-error-markers-never-passes-a-fabricated-number
  (testing "an :error-marker field (upstream ok=false, or a transport throw) is absent, not nil/0/fabricated"
    (let [flaky-http-fn
          (fn [{:keys [url method body] :as req}]
            (if (and (= :post method) (= (str base-url "/_d1") url)
                     (= "organic_pv_growth_pct" (:metric (:args (edn/read-string body)))))
              {:status 200 :body (pr-str {:ok false :error "metric temporarily disabled"})}
              (happy-http-fn req)))
          f  (facts/live-facts {:http-fn flaky-http-fn :json-write pr-str :json-read edn/read-string}
                                base-url secret)
          sm (facts/facts->store-metrics f)]
      (is (facts/error? (get-in f [:metrics :organic-pv-growth-pct]))
          "sanity: the raw facts map really does carry an :error marker here")
      (is (not (contains? sm :organic-pv-growth-pct))
          "the adapter drops the errored field entirely")
      (is (not (contains? sm :organic-pageviews-mom-pct))
          "no alias is created from an errored source field either — never a fabricated number")
      (is (= 12345 (:organic-pageviews sm)) "unrelated fields are unaffected"))))

(deftest facts->store-metrics-on-an-all-error-facts-map-yields-an-empty-map
  (testing "every field failing (e.g. secret rejected) yields {} — with-metrics on {} is a no-op seed, never garbage"
    (let [rejecting-http-fn (fn [_] {:status 403 :body (pr-str {:ok false :error "forbidden"})})
          f  (facts/live-facts {:http-fn rejecting-http-fn :json-write pr-str :json-read edn/read-string}
                                base-url secret)]
      (is (= {} (facts/facts->store-metrics f))))))
