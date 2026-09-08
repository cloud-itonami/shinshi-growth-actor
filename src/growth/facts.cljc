(ns growth.facts
  "Phase 1 read-only LIVE-metrics adapter for club-shinshi's already-live
  internal metrics API (`https://shinshi.club`). `facts->store-metrics`
  (below) adapts a `live-facts` result into the actor's Store shape; the
  OperationActor graph itself is wired against LIVE data via the separate,
  explicit `growth.live` entry point (Phase 2, opt-in — `growth.sim/-main`'s
  own demo default is completely unchanged, still `growth.store/seed-db` +
  `mock-advisor`). Nothing here writes to club-shinshi or to a
  `growth.store/Store` directly. Any execution/auto-commit/publishing
  (Phase 3) is out of scope here.

  Calls two allow-listed, read-only endpoints (ADR-2607040900 follow-up,
  now landed on the club-shinshi side):

    GET  /_metrics/revenue?window_days=7
      → {\"ok\": true, \"revenue\": {\"creator-gmv-jpy\" .. \"creator-net-jpy\" ..
         \"ad-revenue-jpy\" .. \"ad-revenue-usd-minor\" .. \"usd-jpy-rate\" ..
         \"window-days\" 7 \"as-of\" ..}}   ; numeric fields may be null

    POST /_d1  {\"op\": \"read_metric\", \"args\": {\"metric\": <name>}}
      → {\"ok\": true, \"value\": <int-or-null>}
      one call per name in `read-metric-names` (audience/access growth
      allow-list: organic_pageviews, organic_pv_growth_pct, adult_fill_pct,
      general_fill_pct, firehose_referral_pct, d7_return_pct)

  Both endpoints return {\"ok\": false, \"error\": ..} on failure (bad/missing
  secret → 403, misconfiguration → 500).

  auth: header `x-internal-trust: <SHINSHI_GROWTH_READONLY_SECRET>` — the
  secret is NEVER hardcoded, always resolved from the environment (see
  `read-secret!`), and resolved exactly once, at the call boundary
  (`fetch-live-facts!`) — `live-facts` and every per-field fetcher below
  take it as an explicit argument instead of reaching into ambient env
  state.

  WASM/host-injection premise, same contract as `langchain.model` /
  `langchain.kotoba-db`: this namespace performs no I/O itself. `io` is
  {:http-fn   (fn [{:keys [url method headers body]}] => {:status n :body s})
   :json-write (fn [clj-map] => json-string)
   :json-read  (fn [json-string] => clj-map, keyword keys)}.

  Honest-null (捏造ゼロ厳守, matching club-shinshi's own invariant): an
  upstream `null` value passes through as `nil` — a legitimate value, not a
  failure. A transport failure or an upstream `{\"ok\": false}` is instead
  surfaced as an explicit `{:growth.fact/status :error :reason .. :detail ..}`
  marker for that one field (see `error?`) — NEVER a fabricated number, and
  NEVER an uncaught throw. Every fetch is a single attempt; no retries."
  (:require [kotoba.lang.text :as str]
            [growth.store :as store]))

(def readonly-secret-env-var
  "Env var holding the shared secret for club-shinshi's `x-internal-trust`
  header. Never hardcode the value; always resolve from the environment."
  "SHINSHI_GROWTH_READONLY_SECRET")

(def default-base-url "https://shinshi.club")

(def revenue-window-days 7)

(def read-metric-names
  "Allow-listed `read_metric` names relevant to audience/access growth —
  this actor never asks club-shinshi's dispatch route for anything wider."
  ["organic_pageviews" "organic_pv_growth_pct" "adult_fill_pct"
   "general_fill_pct" "firehose_referral_pct" "d7_return_pct"])

;; ───────────────────────── portable env read ─────────────────────────

(defn env
  "Portable environment-variable read: JVM `System/getenv`, Node/nbb
  `process.env`. nil when unset. The ONLY place in this namespace that
  touches ambient env state — every fetch fn below takes `secret` as an
  explicit argument instead (resolved once, here, at the call boundary)."
  [name]
  #?(:clj  (System/getenv name)
     :cljs (some-> (.-env js/process) (aget name))))

(defn read-secret!
  "Resolves the shared secret once. Called only from `fetch-live-facts!` —
  `live-facts` and everything it calls receive the resolved value as a
  plain argument, never re-reading env state themselves."
  []
  (env readonly-secret-env-var))

;; ───────────────────────── error / null handling ─────────────────────────

(defn- error-marker
  "An explicit, non-thrown failure marker for one fact field — distinct
  from both a real value and an honest upstream `nil`."
  [reason detail]
  {:growth.fact/status :error :reason reason :detail detail})

(defn error?
  "True when `v` is one of this namespace's failure markers (as opposed to
  a real value, or `nil` which is a legitimate honest-null upstream value)."
  [v]
  (and (map? v) (= :error (:growth.fact/status v))))

(defn- ok-status? [status]
  (and status (<= 200 status 299)))

(defn- safe
  "Runs zero-arg thunk `f` (one HTTP round trip), catching ANY throw
  (network error, malformed response, injected `http-fn` misbehaving) and
  turning it into an :error marker instead of propagating. This adapter is
  read-only and must never crash its caller on an upstream hiccup."
  [f]
  (try (f)
       (catch #?(:clj Exception :cljs :default) e
         (error-marker :transport (or #?(:clj (ex-message e) :cljs (.-message e)) (str e))))))

;; ───────────────────────── host-capability guard ─────────────────────────

(defn- require-io! [{:keys [http-fn json-write json-read]}]
  (when-not http-fn
    (throw (ex-info ":http-fn must be injected (host capability)" {})))
  (when-not json-read
    (throw (ex-info ":json-read must be injected (host capability)" {})))
  (when-not json-write
    (throw (ex-info ":json-write must be injected (host capability, needed for POST /_d1)" {}))))

;; ───────────────────────── per-field fetchers ─────────────────────────

(defn- fetch-revenue
  "GET /_metrics/revenue?window_days=7 → the `:revenue` submap, or an
  :error marker (never throws)."
  [{:keys [http-fn json-read]} base-url secret]
  (safe
   (fn []
     (let [{:keys [status body]}
           (http-fn {:url (str base-url "/_metrics/revenue?window_days=" revenue-window-days)
                     :method :get
                     :headers {"x-internal-trust" secret}})]
       (if-not (ok-status? status)
         (error-marker :http-status {:status status :body body})
         (let [{:keys [ok revenue error]} (json-read body)]
           (if ok
             revenue
             (error-marker :upstream (or error "revenue endpoint returned ok=false")))))))))

(defn- metric-key
  "\"organic_pv_growth_pct\" → :organic-pv-growth-pct — kebab-case, matching
  every other metric key this codebase already uses (`growth.store/demo-data`)."
  [metric-name]
  (keyword (str/replace metric-name "_" "-")))

(defn- fetch-metric
  "POST /_d1 {op: read_metric, args: {metric: metric-name}} → the metric's
  value (a number, or nil for an honest upstream null), or an :error marker
  (never throws)."
  [{:keys [http-fn json-write json-read]} base-url secret metric-name]
  (safe
   (fn []
     (let [{:keys [status body]}
           (http-fn {:url (str base-url "/_d1")
                     :method :post
                     :headers {"content-type" "application/json"
                               "x-internal-trust" secret}
                     :body (json-write {:op "read_metric" :args {:metric metric-name}})})]
       (if-not (ok-status? status)
         (error-marker :http-status {:status status :body body})
         (let [{:keys [ok value error]} (json-read body)]
           (if ok
             value ; honest NULL passthrough — nil is a legitimate upstream value
             (error-marker :upstream (or error "read_metric returned ok=false")))))))))

;; ───────────────────────── public API ─────────────────────────

(defn live-facts
  "Pure(-ish) Phase 1 core: given the injected `io` map ({:http-fn ..
  :json-write .. :json-read ..} — the same host-capability contract as
  `langchain.model`/`langchain.kotoba-db`), a `base-url`, and an
  already-resolved `secret`, fetches read-only live club-shinshi metrics
  and assembles a facts map:

    {:growth.tenant/id \"club-shinshi\"
     :revenue {:creator-gmv-jpy .. :creator-net-jpy .. :ad-revenue-jpy ..
               :ad-revenue-usd-minor .. :usd-jpy-rate .. :window-days 7
               :as-of ..}                    ; or an :error marker
     :metrics {:organic-pageviews ..         ; one POST /_d1 read_metric
               :organic-pv-growth-pct ..     ; call per allow-listed name;
               :adult-fill-pct ..            ; each value is a number, nil
               :general-fill-pct ..          ; (honest upstream null), or
               :firehose-referral-pct ..     ; an :error marker
               :d7-return-pct ..}}

  This EXTENDS (does not replace) the `:metrics` map shape
  `growth.growthllm` already reads via `growth.store/metric` — the mock
  advisor / Phase 0 store path is completely unchanged by this fn existing.
  Never throws: every per-field failure is caught and surfaced as
  `{:growth.fact/status :error :reason .. :detail ..}` — see `error?`. A
  single attempt per fact; no retries."
  [io base-url secret]
  (require-io! io)
  {:growth.tenant/id store/tenant-id
   :revenue (fetch-revenue io base-url secret)
   :metrics (into {}
                  (map (fn [metric-name] [(metric-key metric-name)
                                          (fetch-metric io base-url secret metric-name)]))
                  read-metric-names)})

(defn fetch-live-facts!
  "Convenience entry point: resolves `SHINSHI_GROWTH_READONLY_SECRET` from
  the environment exactly ONCE, here, then delegates to the pure
  `live-facts` core (see its docstring for the returned shape). `io` — see
  `live-facts`. `base-url` defaults to `default-base-url`."
  [io & [base-url]]
  (live-facts io (or base-url default-base-url) (read-secret!)))

;; ───────────────────────── store adapter (Phase 2) ─────────────────────────

(defn facts->store-metrics
  "Adapts a `live-facts` result into the flat metric-key→value map
  `growth.store/with-metrics` expects. `:error?` markers are DROPPED, never
  passed through — a metric growth-LLM never asked for is simply absent,
  same as an untouched key in `growth.store/demo-data`; it must never look
  like a real number.

  Every non-error `:revenue`/`:metrics` field is kept under its own live key
  name (e.g. `:creator-gmv-jpy`, `:organic-pageviews`) — this EXTENDS
  demo-data's 3-key set, it doesn't narrow to it. `:organic-pv-growth-pct`
  is additionally aliased to `:organic-pageviews-mom-pct` (demo-data's own
  name for the same H1-gate metric, see `growth.store/demo-data`) so an
  existing `:metric-reads [:organic-pageviews-mom-pct]` request (e.g.
  `growth.sim`'s op1) reads real data unchanged when pointed at a live-
  seeded store instead of the demo one."
  [facts]
  (let [;; `:revenue` can itself BE a single error marker (fetch-revenue's
        ;; whole-request failure, e.g. :http-status/:transport) rather than a
        ;; submap of individually-fetched fields (unlike `:metrics`, which is
        ;; always assembled one `fetch-metric` call per name) — check that
        ;; case first, or its :reason/:detail keys would be mistaken for real
        ;; revenue fields by the per-key filter below.
        safe-map (fn [m] (if (error? m) {} (into {} (remove (fn [[_ v]] (error? v))) m)))
        revenue' (safe-map (:revenue facts))
        metrics' (safe-map (:metrics facts))]
    (cond-> (merge revenue' metrics')
      (contains? metrics' :organic-pv-growth-pct)
      (assoc :organic-pageviews-mom-pct (:organic-pv-growth-pct metrics')))))
