(ns growth.operation
  "OperationActor — one growth-loop operation = one supervised actor run,
  expressed as a langgraph-clj StateGraph. The advisor (growth-LLM) is
  sealed into a single node (:advise); its proposal is ALWAYS routed through
  the MarketingGovernor (:govern) and the rollout phase gate (:decide)
  before anything commits to the SSoT.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store     (MemStore | DatomicStore | kotoba-server) — `store` arg
    - the Advisor   (mock | real LLM)                          — :advisor opt
    - the Phase     (0→3 rollout)                              — :phase in ctx
    - the Publisher (mock | real app-aozora, `growth.aozora`)  — :publisher opt

  The Publisher is the actor's outbound SPEECH surface — NOT actuation. After
  a proposal successfully commits, `:commit` calls `publish!` for the actor's
  own social-post announcements (`:marketing-copy` / `:creator-outreach`
  only) and records the outcome as an audit fact. It never calls the network
  directly except through the injected Publisher (default: `mock-publisher`,
  so every caller that doesn't pass `:publisher` keeps today's behavior
  unchanged).

  One graph run = one growth operation (intake → advise → govern → decide →
  commit | hold | approval). No unbounded inner loop — each operation is
  auditable and checkpointed.

  Human-in-the-loop = real approval workflow:
  `interrupt-before #{:request-approval}` pauses the actor and hands the
  decision to a human approver (itonami operator acting on club-shinshi's
  behalf). The approver resumes with `{:approval {:status :approved}}`
  (or `:rejected`)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [growth.growthllm :as growthllm]
            [growth.governor :as governor]
            [growth.phase :as phase]
            [growth.store :as store]
            [growth.publisher :as publisher]))

(def ^:private publishable-ops
  "Effects that are the actor's own SPEECH (a social-post announcement) —
  NOT `:content-experiment` (changes product UI/content, not an announcement)
  and NOT the three high-stakes ops (`:pricing-experiment` /
  `:ad-spend-change` / `:ppv-terms-change`; those never reach `:commit` at
  today's phases without a human approval, and are not announcements either)."
  #{:marketing-copy :creator-outreach})

(defn- commit-fact [request context proposal]
  {:t          :growth.effect/committed
   :op         (:op request)
   :actor      (:actor-id context)
   :disposition :commit
   :basis      (:cites proposal)
   :summary    (:summary proposal)
   :effect     (:effect proposal)})

(defn- commit-record [request proposal]
  {:effect  (:effect proposal)
   :path    [(or (:experiment-id request) (name (:op request)))]
   :payload {:summary (:summary proposal) :rationale (:rationale proposal)
             :cites (:cites proposal)}})

(defn build
  "Compiles an OperationActor graph bound to `store` (any `growth.store/Store`).
  opts:
    :advisor      — a `growth.growthllm/Advisor` (default: mock-advisor)
    :checkpointer — langgraph checkpointer (default: in-mem)
    :publisher    — a `growth.publisher/Publisher` (default: mock-publisher;
                    every existing caller that omits this keeps today's
                    behavior unchanged)"
  [store & [{:keys [advisor checkpointer publisher]
             :or   {advisor      (growthllm/mock-advisor)
                    checkpointer (cp/mem-checkpointer)
                    publisher    (publisher/mock-publisher)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; injected actor-id/phase
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}   ; :commit | :hold | :escalate
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; growth-LLM inference (the contained intelligence node) — proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (growthllm/-advise advisor store request)]
            {:proposal p :audit [(growthllm/trace request p)]})))

      ;; MarketingGovernor — independent censor (separate system than the LLM).
      (g/add-node :govern
        (fn [{:keys [request proposal]}]
          {:verdict (governor/check request proposal store)}))

      ;; Decide: policy disposition, then the rollout-phase gate (which can
      ;; only add caution). HARD policy violations → HOLD (no override).
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (governor/hold-fact request verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t :growth.audit/approval-requested
                        :op (:op request)
                        :reason (or reason
                                    (cond (:high-stakes? verdict) :high-stakes
                                          :else :low-confidence))
                        :phase ph
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request proposal)}))))

      ;; Approval handoff — paused by interrupt-before; a human approver
      ;; resumes with :approval. Then route commit/hold.
      (g/add-node :request-approval
        (fn [{:keys [request proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request proposal)
                            :payload {:summary (:summary proposal)
                                      :approved-by (:by approval)})
             :audit [{:t :growth.audit/approval-granted :op (:op request)
                      :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (governor/hold-fact request
                                                (assoc verdict :violations
                                                       [{:rule :approver-rejected}]))
                            {:t :growth.audit/approval-rejected})]})))

      ;; Commit — the ONLY node that writes the SSoT + audit ledger. After the
      ;; SSoT + effect-committed fact land, a low-stakes "own speech" proposal
      ;; (:marketing-copy / :creator-outreach) is published via the injected
      ;; Publisher — this is the actor announcing its own cleared proposal,
      ;; not actuation. A publish failure is caught and recorded, never
      ;; thrown uncaught out of :commit.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (store/commit-record! store record)
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            (let [pf (when (contains? publishable-ops (:effect proposal))
                       (try
                         (let [result (publisher/publish! publisher
                                                          {:text (:summary proposal)
                                                           :collection publisher/collection})]
                           {:t :growth.audit/published
                            :op (:op request) :uri (:uri result) :cid (:cid result)})
                         (catch #?(:clj Exception :cljs :default) e
                           {:t :growth.audit/publish-failed
                            :op (:op request) :error (ex-message e)})))]
              (when pf (store/append-ledger! store pf))
              {:audit (cond-> [f] pf (conj pf))}))))

      ;; Hold — write the rejection to the ledger; no SSoT mutation.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:growth.decision/hold :growth.audit/approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
