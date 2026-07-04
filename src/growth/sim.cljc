(ns growth.sim
  "Demo runner: push a handful of growth-loop proposals through one
  OperationActor and watch the MarketingGovernor + approval workflow earn
  the growth-LLM the right to commit.

    op1  low-stakes content experiment, clean, high confidence  → commit (Phase 3 only; HOLD at Phase 0)
    op2  marketing copy laced with fake-urgency language        → charter-clean REJECT → hold
    op3  proposal that changes the age-verification copy        → age-verification REJECT → hold
    op4  PPV-terms change (implies creator-split change)        → always escalate → human approves → commit

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [growth.store :as store]
            [growth.operation :as op]
            [growth.phase :as phase]
            [growth.report :as report]))

(defn- line [& xs] (println (apply str xs)))

(defn- run-op!
  [actor thread-id request context approve?]
  (let [res (g/run* actor {:request request :context context} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  承認ワークフロー — 人間承認者がレビュー中 (reason: "
                (-> res :state :audit last :reason) ")")
          (let [res2 (g/run* actor
                             {:approval {:status (if approve? :approved :rejected)
                                         :by "itonami-operator"}}
                             {:thread-id thread-id :resume? true})]
            (line "   ▶  承認" (if approve? "可決" "却下") " → disposition = "
                  (get-in res2 [:state :disposition]))
            res2))
      (do (line "   → disposition = " (get-in res [:state :disposition])
                "  (confidence " (get-in res [:state :verdict :confidence]) ")")
          res))))

(defn -main [& _]
  (let [db    (store/seed-db)
        actor (op/build db)
        ctx   {:actor-id "growth-llm" :phase 3}] ; phase 3 shown for illustration only — this repo's own default is Phase 0

    (line "── BMC hypothesis backlog (club-shinshi) ──")
    (line (report/hypothesis-backlog-text db))

    (line "\n── OperationActor (growth-LLM sealed; MarketingGovernor active) ──")

    (line "\nop1  content-experiment（クリーンな低確信度以上の提案・低stake）")
    (run-op! actor "op1"
             {:op :content-experiment :hyp-id :H1 :metric-reads [:organic-pageviews-mom-pct]}
             ctx true)

    (line "\nop2  marketing-copy — 「今だけ限定！残りわずか」のような訴求文言")
    (run-op! actor "op2"
             {:op :marketing-copy :hyp-id :H1 :dark-pattern? true}
             ctx true)

    (line "\nop3  content-experiment — 年齢確認/同意コピーの変更を提案（禁止）")
    (run-op! actor "op3"
             {:op :content-experiment :touches-age-gate? true}
             ctx true)

    (line "\nop4  ppv-terms-change — creator-split を変更する提案（常に人間承認）")
    (run-op! actor "op4"
             {:op :ppv-terms-change :hyp-id :H2 :metric-reads [:ad-revenue-jpy :creator-gmv-jpy]}
             ctx true)

    (line "\n── 監査台帳 (append-only) ──")
    (line (report/audit-ledger-text db))

    ;; ── Phase 0→3 段階導入: 同じ「クリーンな低stake提案」が phase で変わる ──
    (line "\n── 段階導入 Phase 0→3 (同一のクリーンな content-experiment を phase 別に) ──")
    (doseq [ph [0 1 2 3]]
      (let [s2 (store/seed-db)
            a2 (op/build s2)
            r  (g/run* a2 {:request {:op :content-experiment :hyp-id :H1
                                      :metric-reads [:organic-pageviews-mom-pct]}
                           :context (assoc ctx :phase ph)}
                       {:thread-id (str "phase-" ph)})]
        (line "  phase " ph " (" (:label (phase/phases ph)) "): "
              (if (= :interrupted (:status r))
                "⏸ 人間承認へ"
                (str (get-in r [:state :disposition])
                     (when-let [pr (-> (store/ledger s2) last :phase-reason)]
                       (str " (" pr ")")))))))
    (line "\ndone. this repo's own default-phase is 0 (see growth.phase).")))
