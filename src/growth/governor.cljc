(ns growth.governor
  "MarketingGovernor — the independent compliance layer that earns the
  growth-LLM the right to commit. The LLM has no notion of charter-clean
  marketing practice, adult-content-compliance boundaries or creator-payout
  protection, so this MUST be a separate system able to *reject* a proposal
  and fall back to HOLD (write nothing) — the growth-loop analog of
  talent.policy / itonami.governor / robotaxi's Minimal Risk Condition.

  HARD invariants (a human approver CANNOT override these — you don't get to
  approve your way past a charter violation):
    1. Charter-clean       — no dark-pattern language (fake urgency/scarcity,
                             hidden fees, misleading auto-renew). This is a
                             HEURISTIC FLOOR (keyword/phrase scan), not a full
                             NLP classifier — it catches the obvious cases and
                             is meant to be tightened over time, not trusted
                             as complete.
    2. Age-verification/consent-copy untouchable — any proposal that touches
                             age-verification or consent copy is rejected
                             outright; the actor must never propose changes
                             there (ExoClick / adult-content compliance).
    3. Unknown effect       — an `:effect` outside the enum is a hard
                             violation (fail closed on schema drift).

  SOFT (a human decides; the actor may still recommend, but never auto-commits):
    4. Creator-payout protection — `:effect :ppv-terms-change`, or ANY
                             proposal whose :cites/:summary/:rationale implies
                             changing the creator GMV split, is ALWAYS
                             escalate — regardless of confidence. Never
                             eligible for auto-commit.
    5. High-stakes gate     — #{:pricing-experiment :ad-spend-change
                             :ppv-terms-change} always requires human
                             approval, clean or not.
    6. Confidence floor     — low-stakes proposals
                             (#{:content-experiment :marketing-copy
                             :creator-outreach}) are only auto-commit
                             eligible when :ok? is true (no violations) AND
                             :confidence >= 0.7."
  (:require [clojure.string :as str]))

;; ───────────────────────── policy tables ─────────────────────────

(def high-stakes
  "Operations grave enough to always require a human, even when clean."
  #{:pricing-experiment :ad-spend-change :ppv-terms-change})

(def low-stakes
  "Operations eligible for auto-commit (Phase 3 only) when policy-clean and
  above the confidence floor."
  #{:content-experiment :marketing-copy :creator-outreach})

(def valid-effects (into high-stakes low-stakes))

(def confidence-floor 0.7)

(def dark-pattern-markers
  "Heuristic floor, not a full NLP classifier — obvious fake-urgency/
  scarcity, hidden-fee and misleading-auto-renew markers (JP + EN). Extend as
  real cases are found; never treat absence-of-match as proof of cleanliness."
  #{"今だけ" "残りわずか" "本日限り" "今すぐ買わないと" "在庫僅少"
    "limited time only" "act now" "hurry" "only a few left" "while supplies last"
    "追加料金は購入後" "後から追加料金" "hidden fee" "surprise fee"
    "自動更新をキャンセルしにくく" "解約しづらく" "silently renews" "auto-renews without notice"})

(def age-consent-markers
  #{:age-verification :age-gate :consent-copy
    "age-verification" "age verification" "age gate" "consent copy"
    "年齢確認" "同意コピー" "同意画面"})

(def creator-split-markers
  #{:creator-split :gmv-split :revenue-split :creator-share :payout-split
    "creator-split" "gmv-split" "revenue-split" "creator-share" "payout-split"
    "取り分" "分配率" "レベニューシェア" "creator gmv split" "creator payout"})

;; ───────────────────────── checks ─────────────────────────

(defn- text-of [proposal]
  (str/lower-case (str (:summary proposal) " " (:rationale proposal))))

(defn- cited-set [proposal]
  (set (map (fn [c] (if (keyword? c) c (str/lower-case (str c)))) (:cites proposal))))

(defn- any-marker? [text cited markers]
  (boolean (or (some #(and (string? %) (str/includes? text (str/lower-case %))) markers)
               (some cited markers))))

(defn- unknown-effect-violations [proposal]
  (when-not (contains? valid-effects (:effect proposal))
    [{:rule :unknown-effect :detail (str "effect が enum 外: " (pr-str (:effect proposal)))}]))

(defn- charter-clean-violations [proposal]
  (when (any-marker? (text-of proposal) (cited-set proposal) dark-pattern-markers)
    [{:rule :charter-clean
      :detail "ダークパターン疑いの表現を検出（誇大な緊急性/希少性・隠れ手数料・誤解を招く自動更新）"}]))

(defn- age-verification-violations [proposal]
  (when (any-marker? (text-of proposal) (cited-set proposal) age-consent-markers)
    [{:rule :age-verification-untouchable
      :detail "年齢確認/同意コピーへの変更提案は常に拒否（ExoClick/アダルトコンテンツ コンプライアンス）"}]))

(defn creator-payout-touch?
  "True when a proposal is, or implies, a change to the creator GMV split.
  Always escalate regardless of confidence — never auto-commit eligible."
  [proposal]
  (or (= :ppv-terms-change (:effect proposal))
      (any-marker? (text-of proposal) (cited-set proposal) creator-split-markers)))

(defn check
  "Censors a growth-LLM proposal against the policy tables. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}.

   - :hard?       — at least one HARD violation (unknown-effect / charter-clean /
                    age-verification). Forces HOLD; a human cannot override.
   - :escalate?   — soft: low confidence OR high-stakes OR creator-payout-touch.
                    A human decides.
   - :ok?         — clean AND not escalating: safe to auto-commit."
  [_request proposal _st]
  (let [hard    (into [] (concat (unknown-effect-violations proposal)
                                 (charter-clean-violations proposal)
                                 (age-verification-violations proposal)))
        conf    (:confidence proposal 0.0)
        low?    (< conf confidence-floor)
        stakes? (or (contains? high-stakes (:effect proposal))
                    (creator-payout-touch? proposal))
        hard?   (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     ;; soft escalation only matters when there is no hard violation — a hard
     ;; violation always wins and goes straight to HOLD.
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request verdict]
  {:t          :growth.decision/hold
   :op         (:op request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
