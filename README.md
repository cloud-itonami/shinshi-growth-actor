# shinshi-growth-actor

A **growth-LLM ⊣ MarketingGovernor** actor — the growth-loop-as-a-service
itonami (operated by **Gftd Japan株式会社**, [`cloud-itonami`](https://github.com/gftdcojp/cloud-itonami))
provides to **club-shinshi** (operated by **JK株式会社**, a separate legal
entity — see ADR-2607021500 in the superproject). This is a **new client
relationship**: as of this repo's creation, `cloud-itonami` has no existing
reference to club-shinshi/shinshi, and club-shinshi has no reference to
itonami. This actor is greenfield — it does not assume or depend on any
prior integration between the two.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj) StateGraph
runtime (portable `.cljc`, supervised superstep loop, interrupts, Datomic/
in-mem checkpoints) — the same containment pattern as
[`com-etzhayyim-kyoninka`](https://github.com/etzhayyim/com-etzhayyim-kyoninka)
(robotaxi-actor), [`gftd-talent-actor`](https://github.com/gftdcojp/gftd-talent-actor)
and [`cloud-itonami`](https://github.com/gftdcojp/cloud-itonami)'s own
ops-LLM ⊣ CertGovernor actor.

> **Why an actor layer at all?** A growth-LLM is good at drafting content
> experiments, pricing tests, ad-spend changes, creator outreach and
> marketing copy — but it has **no notion of charter-clean marketing
> practice, adult-content-compliance boundaries or creator-payout
> protection**. Letting it act directly on club-shinshi's behalf invites dark
> patterns, age-verification tampering and quietly shifting the creator GMV
> split. This project seals the growth-LLM into a single node and wraps it
> with an independent **MarketingGovernor**, a human **approval workflow**,
> and an immutable **audit ledger**.

See the accompanying superproject ADR,
[`90-docs/adr/2607040900-shinshi-growth-actor-itonami-client-service.md`](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607040900-shinshi-growth-actor-itonami-client-service.md),
for the full decision record, and [`docs/business-model.md`](docs/business-model.md) /
[`docs/operator-guide.md`](docs/operator-guide.md) for the service relationship.

## Phase 0 scope (this repo, as committed)

**This build stays at Phase 0**: mock advisor + in-memory Store only, no live
club-shinshi data, no real execution. See `src/growth/phase.kotoba` for the
full 0→3 rollout ladder and `src/growth/facts.clj` for the (stub-only, not
wired) production data-adapter shape. Phase 1 (read-only live metrics) and
Phase 2 (real LLM against live data, still human-gated) are separate,
not-yet-built follow-ups — see the ADR's open follow-ups.

## The core contract

```
request (desired growth-loop experiment)
        │
        ▼
   ┌───────────┐     proposal      ┌───────────────────┐
   │ growth-LLM│ ────────────────▶ │ MarketingGovernor  │  (independent system)
   │ (sealed)  │  draft + rationale│  charter-clean ·    │
   └───────────┘                   │  age-verification · │
                            commit ◀┤  creator-payout ·   ├─▶ hold (規程違反; 上書き不可)
                                │   │  high-stakes gate    │
                          SSoT + 台帳└───────┬────────────┘
                                       escalate ─▶ 人間承認 (interrupt)
```

**growth-LLM never commits a change the MarketingGovernor would reject.**
Hard violations (unknown effect / dark-pattern copy / age-verification-or-
consent-copy edits) fall back to **hold** and *cannot* be overridden by a
human. Soft cases (low confidence / high-stakes / creator-payout-touching)
always go to the human approval workflow — even when the proposal is
otherwise clean and high-confidence.

The deterministic, privacy-safe aggregate metric contract for acquisition, activation, conversion, paid net revenue, and retention is defined in `src/growth/metrics.kotoba`, with a typed EDN fixture in `test/fixtures/growth_metrics.edn`. Formula, versioning, rounding, null, cohort, and operator rules are documented in `docs/operator-guide.md`.

## Layout

| File | Actor / role |
|---|---|
| `src/growth/growthllm.kotoba` | **Advisor** protocol — `mock-advisor` (default, only advisor wired in Phase 0) ‖ `llm-advisor` (real `langchain.model` ChatModel, not wired) |
| `src/growth/governor.kotoba` | **MarketingGovernor** — charter-clean · age-verification-untouchable · unknown-effect (hard); creator-payout-protection · high-stakes · confidence-floor (soft) |
| `src/growth/phase.kotoba` | **Phase 0→3 rollout** — this repo's own `default-phase` is **0** |
| `src/growth/operation.kotoba` | **OperationActor** — langgraph-clj StateGraph (1 run = 1 growth-loop op); Store/Advisor/Phase injected |
| `src/growth/store.kotoba` | **Store** protocol — `MemStore` (default) ‖ `DatomicStore` (`langchain.db`, swappable to Datomic Local / kotoba-server) + append-only ledger, `:growth.tenant/id "club-shinshi"` tagged |
| `src/growth/facts.clj` | **production adapter STUB** — NOT wired; documents the `ai-gftd-shinshi` internal D1 dispatch API this would eventually call, and the secrets-sharing decision that blocks it |
| `src/growth/report.kotoba` | plain-text views over the hypothesis backlog / experiment ledger / audit ledger |
| `src/growth/sim.kotoba` | demo driver (`kbb -M:dev:run`) |
| `src/growth/metrics.kotoba` | versioned aggregate metric definitions, closed validation, and deterministic integer calculations |
| `src/growth/tenant_onboarding.kotoba` | closed offline EDN contract for tenant identity, two-human approval, allowed capabilities, and audit ownership; never provisions credentials |
| `src/growth/revenue_agent.kotoba` | bounded governed revenue tick: consumes injected live read-only aggregate facts, enforces tenant capabilities and explicit human decisions, ranks approved experiments, and emits audit events without actuation |
| `test/growth/metrics_test.kotoba` | golden fixture, privacy, validation, rounding, zero-denominator, and formula tests |
| `test/growth/contract_test.kotoba` | MemStore ≡ DatomicStore parity · no-actuation (malicious proposal → hold) · high-stakes-always-escalates · dark-pattern-rejected · confidence-floor · phase-0-holds-everything — **13 tests / 44 assertions, 0 failures** |

## Run

```bash
kbb -M:dev:run     # demo: content-experiment / dark-pattern / age-gate / ppv-terms across Phase 0→3
kbb -M:dev:test    # governor contract · store parity (Mem≡Datomic)
kbb -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Status

Phase 0 scaffold: mock advisor + MemStore/DatomicStore + MarketingGovernor +
langgraph-clj StateGraph + contract tests. Runnable, 13 tests / 44 assertions
/ 0 failures, `MemStore ≡ DatomicStore` proven by contract test. Not done:
Phase 1 live-metrics adapter (blocked on a cross-repo secrets-sharing
decision with `ai-gftd-shinshi`), Phase 2 real-LLM wiring, and
cloud-itonami's tenant/external-onboarding extension needed before this can
be a "real" itonami-hosted client tenant (ADR-0009 gap) — see the ADR.

## License

Private (`gftdcojp` org default visibility).
