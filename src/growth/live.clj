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

  growth-LLM advisor: this entry point (ONLY this entry point --
  growth.sim/-main stays mock-advisor, unchanged) wires a REAL LLM --
  growth.murakumo/murakumo-model (itonami's self-hosted murakumo.cloud
  gateway, qwen-agentworld-35b-a3b) via growth.growthllm/llm-advisor. The
  advisor's own proposal is still censored end-to-end by the same
  MarketingGovernor/phase gate as the mock path -- swapping the advisor
  never changes that invariant.

  Publisher: also wires a REAL growth.aozora/aozora-publisher (this
  entry point ONLY -- op/build's own default stays mock-publisher for every
  other caller). Loads/creates this actor's own persisted identity at
  `.growth/identity.edn` (gitignored; see growth.cacao) -- the actor's own
  did:key, self-sovereign, never a human-handed token. A committed
  :marketing-copy / :creator-outreach proposal (see growth.operation's
  publishable-ops) is published for real to the aozora PDS under that DID.

  Run: clojure -M:dev:run-live"
  (:require [langchain.jvm :as jvm]
            [langgraph.graph :as g]
            [growth.facts :as facts]
            [growth.growthllm :as growthllm]
            [growth.murakumo :as murakumo]
            [growth.cacao :as cacao]
            [growth.aozora :as aozora]
            [growth.store :as store]
            [growth.operation :as op]
            [growth.report :as report]))

(def ^:private io
  {:http-fn jvm/jvm-http-fn :json-write jvm/json-write :json-read jvm/json-read})

(def ^:private identity-path
  "This actor's persisted Ed25519 identity — gitignored, generated on first
  use by growth.cacao/load-or-create-identity! if absent."
  ".growth/identity.edn")

(defn -main [& _]
  (if-not (facts/read-secret!)
    (do (println (str facts/readonly-secret-env-var " is not set in the environment "
                       "— cannot fetch live club-shinshi facts."))
        (System/exit 1))
    (let [live         (facts/live-facts io facts/default-base-url (facts/read-secret!))
          live-metrics (facts/facts->store-metrics live)
          db           (store/with-metrics (store/seed-db) live-metrics)
          token        (murakumo/read-token!)
          chat-model   (murakumo/murakumo-model
                        (assoc io :api-key token))
          advisor      (growthllm/llm-advisor chat-model)
          identity     (cacao/load-or-create-identity! identity-path)
          publisher    (aozora/aozora-publisher (assoc io :identity identity))
          actor        (op/build db {:advisor advisor :publisher publisher})
          ctx          {:actor-id "growth-llm" :phase 3}]
      (println "── advisor ──")
      (println (str "  murakumo (real LLM, " murakumo/default-model " via " murakumo/default-url ")"
                     (if token
                       (str " — " murakumo/token-env-var " set, x-api-key sent")
                       (str " — " murakumo/token-env-var " not set, x-api-key omitted"))
                     " — NOT growth.sim/-main's mock-advisor"))
      (println "── publisher ──")
      (println (str "  aozora (REAL, " (:did identity) " via " aozora/default-pds ")"
                     " — NOT op/build's own default mock-publisher"))
      (println "\n── LIVE club-shinshi facts ──")
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

      (println "\n── op2  marketing-copy against LIVE :organic-pv-growth-pct (publishable — see")
      (println "        growth.operation/publishable-ops) ──")
      (let [res (g/run* actor {:request {:op :marketing-copy :hyp-id :H1
                                         :metric-reads [:organic-pv-growth-pct]}
                               :context ctx}
                        {:thread-id "live-op2"})
            res (if (= :interrupted (:status res))
                  (g/run* actor
                          {:approval {:status :approved :by "itonami-operator"}}
                          {:thread-id "live-op2" :resume? true})
                  res)]
        (println "  → disposition =" (get-in res [:state :disposition])
                 " (confidence" (get-in res [:state :verdict :confidence]) ")")
        (when-let [pub (last (filter #(#{:growth.audit/published :growth.audit/publish-failed} (:t %))
                                     (:audit (:state res))))]
          (println "  →" (name (:t pub)) (pr-str (dissoc pub :t)))))

      (println "\n── audit ledger ──")
      (println (report/audit-ledger-text db))
      (println "\ndone. growth.phase/default-phase is still 0 — this is an opt-in Phase 2 run,")
      (println "not a change to growth.sim/-main's own demo default."))))
