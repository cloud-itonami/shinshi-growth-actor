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
                                execution. **This build's own default-phase
                                stays at Phase 0.**
    Phase 1  read-only live   — read-only live club-shinshi metrics are now
                                a real, callable capability (`growth.facts/
                                live-facts` + `fetch-live-facts!`, no longer
                                a stub) — still no writes, and still not
                                wired into `growth.operation`'s
                                OperationActor graph (that wiring, plus an
                                advisor that actually consumes live facts,
                                is Phase 2). `:live-facts?` below is the
                                phase-state marker a future caller checks
                                before calling `growth.facts`.
    Phase 2  assisted         — `growth.facts` IS now wired into the
                                OperationActor graph, via the separate,
                                explicit `growth.live` entry point
                                (`facts->store-metrics` seeds a `Store` from
                                live club-shinshi data, then `growth.operation/
                                build` runs unchanged against it) — opt-in,
                                NOT `growth.sim/-main`'s own demo default,
                                which stays `seed-db` + `mock-advisor`
                                unmodified. `llm-advisor` against live data is
                                still not wired (mock-advisor only). Every
                                write still needs human approval regardless.
    Phase 3  supervised auto  — the eventual target: policy-clean,
                                high-confidence LOW-STAKES writes may
                                auto-commit; high-stakes / creator-payout
                                proposals still always escalate via the
                                governor regardless of phase.

  Phase 2 is a separate follow-up build requiring its own validation (see
  the accompanying ADR's open follow-ups: cloud-itonami tenant/external-
  onboarding extension) before Phase 3 is reachable. The ai-gftd-shinshi
  internal dispatch-API secrets-sharing decision that used to block Phase 1
  is resolved as of this build: club-shinshi's read-only metrics endpoints
  are live and `growth.facts` calls them (see that namespace).

  `gate` runs AFTER `governor/check`, taking the policy disposition
  (:commit | :escalate | :hold) and returning the phase-adjusted disposition
  plus a reason when the phase changed it. `gate` itself is UNCHANGED by
  Phase 1 landing — :writes/:auto for phases 0 and 1 are both empty, so the
  write-gating behavior at phase 1 is identical to phase 0 (hold
  everything); only :live-facts? differs.")

(def write-ops
  #{:content-experiment :pricing-experiment :ad-spend-change
    :creator-outreach :ppv-terms-change :marketing-copy})

(def low-stakes-ops
  #{:content-experiment :marketing-copy :creator-outreach})

(def phases
  "phase → {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when policy-clean> :live-facts? <may call growth.facts/
  live-facts for read-only live club-shinshi metrics>}."
  {0 {:label "scaffold-only"    :writes #{}         :auto #{}              :live-facts? false}
   1 {:label "read-only-live"   :writes #{}         :auto #{}              :live-facts? true}
   2 {:label "assisted-live"    :writes write-ops    :auto #{}              :live-facts? true}
   3 {:label "supervised-auto"  :writes write-ops    :auto low-stakes-ops   :live-facts? true}})

;; This build's own default-phase stays at Phase 0 (scaffold: mock advisor +
;; MemStore only, no live data, no execution) — see the accompanying ADR.
;; Phase 1 (live-facts-enabled) is now reachable in code by passing
;; `{:phase 1}` in an operation's context; it is just not this repo's
;; default yet.
(def default-phase 0)

(defn live-facts-enabled?
  "True from Phase 1 onward — the phase-state marker a caller checks before
  invoking `growth.facts/live-facts` (or `fetch-live-facts!`). Still
  strictly read-only: this predicate never affects `:writes`/`:auto`, and
  `growth.operation`'s OperationActor graph does not call `growth.facts` in
  this build regardless of phase — that wiring is a Phase 2 follow-up."
  [phase]
  (boolean (:live-facts? (get phases phase (get phases default-phase)))))

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
