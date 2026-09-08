(ns growth.growthllm
  "growth-LLM client — the *contained intelligence node*.

  It proposes growth-loop experiments for club-shinshi (content experiments,
  pricing experiments, ad-spend changes, creator outreach, PPV-terms changes,
  marketing-copy changes) on itonami's behalf. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a rationale +
  the fields/hypotheses it cited), never a committed record. Every output is
  censored downstream by `growth.governor` before anything touches the SSoT.

  Like talent.hrllm / itonami.regllm, this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised end-to-end.
  In production this calls a real LLM with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why — SCANNED by the charter-clean gate
     :cites      [kw|str ..]    ; BMC hypothesis id + metric keys used — SCANNED too
     :effect     kw             ; #{:content-experiment :pricing-experiment
                                ;   :ad-spend-change :creator-outreach
                                ;   :ppv-terms-change :marketing-copy}
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]
            [langchain.model :as model]
            [growth.store :as store]))

(def valid-effects
  #{:content-experiment :pricing-experiment :ad-spend-change
    :creator-outreach :ppv-terms-change :marketing-copy})

;; ───────────────────────── mock proposal generator ─────────────────────────
;; `request` carries the desired experiment kind as `:op` (== the `:effect` a
;; clean proposal would carry) plus test-only injection flags mirroring
;; talent.hrllm's `:bias?`/`:greedy?` idiom — deliberately OOD generations the
;; MarketingGovernor must reject:
;;   :dark-pattern?          → fake urgency/scarcity copy in :summary
;;   :touches-age-gate?      → cites age-verification/consent copy (hard, never allowed)
;;   :touches-creator-split? → implies changing the creator GMV split (always escalate)

(defn- propose
  [db {:keys [op hyp-id metric-reads dark-pattern? touches-age-gate?
              touches-creator-split? confidence]}]
  (let [base-cites (cond-> (vec metric-reads) hyp-id (conj hyp-id))
        conf       (or confidence 0.85)]
    (cond
      touches-age-gate?
      {:summary    "同意/年齢確認(age-verification)コピーの文言をコンバージョン改善のため変更する提案"
       :rationale  "age gate 離脱率が高いため文言を調整したい。"
       :cites      (conj base-cites :age-verification)
       :effect     op
       :confidence conf}

      touches-creator-split?
      {:summary    "クリエイターの取り分(creator-split)を調整する PPV 条件変更の提案"
       :rationale  "ad 収益が伸び悩むため creator-split を引き下げてプラットフォーム取り分を増やす。"
       :cites      (conj base-cites :creator-split)
       :effect     op
       :confidence conf}

      dark-pattern?
      {:summary    "今だけ限定！残りわずかのキャンペーンを訴求する広告コピー案"
       :rationale  "緊急性を煽ることでCVRが上がる想定。"
       :cites      base-cites
       :effect     op
       :confidence conf}

      (contains? valid-effects op)
      {:summary    (str (name op) " の提案 — " (some-> hyp-id name) " 検証のための施策")
       :rationale  (str "実測 " (pr-str (select-keys (into {} (map (juxt identity #(store/metric db %))) metric-reads)
                                                     metric-reads))
                        " に基づく。誇大表現・隠れ手数料・誤解を招く自動更新は含まない。")
       :cites      base-cites
       :effect     op
       :confidence conf}

      :else
      {:summary "未対応の操作" :rationale (str op) :cites [] :effect :noop :confidence 0.0})))

(defn infer
  "Route a request to the proposal generator. request: {:op kw ...}"
  [db request] (propose db request))

;; ───────────────────────── Advisor protocol ─────────────────────────
;; The advisor is injected into the OperationActor, so the contained
;; intelligence node is a swap: a deterministic mock for dev/tests, or a real
;; LLM in production. Either way its output is a PROPOSAL the
;; MarketingGovernor still censors — the single invariant never depends on
;; which advisor ran.

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere;
  the only advisor wired in Phase 0."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは itonami が club-shinshi 向けに運用する growth-LLM です。与えられた"
       "事実(BMC hypothesis backlog / metrics)のみに基づき、提案を1つだけ EDN マップ"
       "で返します。説明や前置きは一切書かず、EDN だけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使ったhypothesis id/metricキーのベクタ) "
       ":effect(:content-experiment|:pricing-experiment|:ad-spend-change|"
       ":creator-outreach|:ppv-terms-change|:marketing-copy) :confidence(0..1)。\n"
       "重要: 誇大な緊急性/希少性・隠れ手数料・誤解を招く自動更新の文言は絶対に提案しない。"
       "年齢確認/同意コピーの変更は絶対に提案しない。クリエイターの取り分(creator-split)"
       "を変更する提案は必ず低確信・要人間承認として扱われる前提で書く。"))

(defn- facts-for [st {:keys [hyp-id metric-reads]}]
  (cond-> {}
    hyp-id        (assoc :hypothesis (store/hypothesis st hyp-id))
    (seq metric-reads) (assoc :metrics (into {} (map (juxt identity #(store/metric st %))) metric-reads))))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure yields
  a safe low-confidence noop so the MarketingGovernor escalates/holds — an
  LLM hiccup can never auto-commit."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference). Pass
  `model/anthropic-model`, an OpenAI-compatible model (Ollama/vLLM/kotoba), or
  `model/mock-model` for offline tests. `gen-opts` is forwarded to -generate.
  NOT wired in Phase 0 — this build only uses `mock-advisor`; `llm-advisor` is
  here so Phase 2 is a swap, not a rewrite."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record — the LLM's interpretable rationale is a
  key asset (growth-loop review, audits). Persisted to the :audit channel."
  [request proposal]
  {:t          :growth.proposal/trace
   :op         (:op request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
