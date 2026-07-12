(ns growth.live
  "Phase 2: growth.facts wired into the OperationActor graph, for real
  (growth.phase's own Phase 2 description). JVM-only demo entry point
  (http-kit/jsonista via kotoba-lang/langchain's langchain.jvm, :run-live
  alias) that proves growth.facts/live-facts -> facts->store-metrics ->
  growth.store/with-metrics -> growth.operation/build is a real, exercised
  path end-to-end against LIVE club-shinshi metrics -- not just a capability
  that exists unused (Phase 1's own limitation, see growth.phase).

  Read-only and additive: the MarketingGovernor, growth.phase/default-phase
  (still 0), and growth.sim/-main's own demo (still seed-db + mock-advisor,
  completely unmodified) are untouched by this file existing. This is a
  SEPARATE, explicit opt-in entry point, not a change to any default.

  Plain .clj (not .cljc): http-kit/jsonista are JVM-only by design (see
  langchain.jvm's own docstring -- langchain-clj's zero-third-party-dep
  promise means every consumer of its JVM host-fn brings its own http-kit/
  jsonista, added here only under the :run-live alias, never the base deps).

  Run: clojure -M:dev:run-live"
  (:require [langchain.jvm :as jvm]
            [langgraph.graph :as g]
            [growth.facts :as facts]
            [growth.store :as store]
            [growth.operation :as op]
            [growth.report :as report]))

(def ^:private io
  {:http-fn jvm/jvm-http-fn :json-write jvm/json-write :json-read jvm/json-read})

(defn -main [& _]
  (if-not (facts/read-secret!)
    (do (println (str facts/readonly-secret-env-var " is not set in the environment "
                       "— cannot fetch live club-shinshi facts."))
        (System/exit 1))
    (let [live         (facts/live-facts io facts/default-base-url (facts/read-secret!))
          live-metrics (facts/facts->store-metrics live)
          db           (store/with-metrics (store/seed-db) live-metrics)
          actor        (op/build db)
          ctx          {:actor-id "growth-llm" :phase 3}]
      (println "── LIVE club-shinshi facts ──")
      (println (pr-str live))
      (println "\n── adapted store metrics (demo-data's :organic-pageviews-mom-pct/:ad-revenue-jpy/")
      (println "   :creator-gmv-jpy keys kept, plus every other live metric under its own key) ──")
      (println (pr-str live-metrics))

      (println "\n── op1  content-experiment against LIVE :organic-pageviews-mom-pct ──")
      (let [res (g/run* actor {:request {:op :content-experiment :hyp-id :H1
                                         :metric-reads [:organic-pageviews-mom-pct]}
                               :context ctx}
                        {:thread-id "live-op1"})]
        (if (= :interrupted (:status res))
          (let [res2 (g/run* actor
                             {:approval {:status :approved :by "itonami-operator"}}
                             {:thread-id "live-op1" :resume? true})]
            (println "  ⏸  approval workflow →  disposition ="
                     (get-in res2 [:state :disposition])))
          (println "  → disposition =" (get-in res [:state :disposition])
                   " (confidence" (get-in res [:state :verdict :confidence]) ")")))

      (println "\n── audit ledger ──")
      (println (report/audit-ledger-text db))
      (println "\ndone. growth.phase/default-phase is still 0 — this is an opt-in Phase 2 run,")
      (println "not a change to growth.sim/-main's own demo default."))))
