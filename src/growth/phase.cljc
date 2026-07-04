(ns growth.phase
  "Phase 0→3 staged rollout — the growth-loop analog of robotaxi's ODD phases
  and itonami's cert-authority ramp: start narrow (no writes, no live data),
  widen as trust grows. Where the MarketingGovernor answers 'is this
  allowed?', the phase answers 'how much autonomy does the actor have *yet*
  for this NEW client (club-shinshi)?'. It can only ever make the actor MORE
  conservative than policy: it downgrades a policy-clean commit to approval
  or hold, never the reverse.

    Phase 0  scaffold-only    — no writes at all. mock-advisor + MemStore
                                only, no live club-shinshi data, no
                                execution. **This build stays at Phase 0.**
    Phase 1  read-only live   — NOT IMPLEMENTED HERE. Would add read-only
                                live club-shinshi metrics (via `growth.facts`,
                                itself a stub) — still no writes.
    Phase 2  assisted         — NOT IMPLEMENTED HERE. Would add `llm-advisor`
                                against live data; every write still needs
                                human approval.
    Phase 3  supervised auto  — the eventual target: policy-clean,
                                high-confidence LOW-STAKES writes may
                                auto-commit; high-stakes / creator-payout
                                proposals still always escalate via the
                                governor regardless of phase.

  Phase 1 and 2 are each separate follow-up builds requiring their own
  validation (see the accompanying ADR's open follow-ups: cloud-itonami
  tenant/external-onboarding extension, and the ai-gftd-shinshi internal
  dispatch-API secrets-sharing decision) before Phase 3 is reachable.

  `gate` runs AFTER `governor/check`, taking the policy disposition
  (:commit | :escalate | :hold) and returning the phase-adjusted disposition
  plus a reason when the phase changed it.")

(def write-ops
  #{:content-experiment :pricing-experiment :ad-spend-change
    :creator-outreach :ppv-terms-change :marketing-copy})

(def low-stakes-ops
  #{:content-experiment :marketing-copy :creator-outreach})

(def phases
  "phase → {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when policy-clean>}."
  {0 {:label "scaffold-only"    :writes #{}                    :auto #{}}
   1 {:label "read-only-live"   :writes #{}                    :auto #{}}
   2 {:label "assisted-live"    :writes write-ops               :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops               :auto low-stakes-ops}})

;; This build stays at Phase 0 (scaffold: mock advisor + MemStore only, no
;; live data, no execution) — see the accompanying ADR.
(def default-phase 0)

(defn gate
  "Adjust a policy disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a policy HOLD always stays HOLD (compliance wins).
  - an op not yet enabled in this phase's :writes → HOLD (:phase-disabled).
    At Phase 0 this holds EVERYTHING (no writes at all).
  - an op enabled but not auto-eligible → ESCALATE (:phase-approval), even
    if policy was clean.
  - phase only ever *adds* caution; it never upgrades an escalate/hold to
    commit."
  [phase {:keys [op]} policy-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold policy-disposition)        {:disposition :hold :reason nil}
      (not (contains? writes op))         {:disposition :hold :reason :phase-disabled}
      (and (= :commit policy-disposition)
           (not (contains? auto op)))     {:disposition :escalate :reason :phase-approval}
      :else                               {:disposition policy-disposition :reason nil})))

(defn verdict->disposition
  "Map a MarketingGovernor verdict to a base disposition before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
