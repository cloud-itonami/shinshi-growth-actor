# Operator Guide

This guide is for the itonami operator (Gftd Japan株式会社) running this
actor on club-shinshi's (JK株式会社) behalf.

## 1. Run the demo

```bash
git clone git@github.com:gftdcojp/shinshi-growth-actor
cd shinshi-growth-actor
clojure -M:dev:test
clojure -M:dev:run
```

The demo uses the built-in synthetic BMC snapshot (`growth.store/demo-data`).
No real club-shinshi data is read or required to run this repo.

## 2. Deterministic metric contract

The versioned contract is implemented by `growth.metrics`; its checked-in aggregate fixture is `test/fixtures/growth_metrics.edn`. It calculates exactly five metrics without network access, secrets, clock reads, random values, or floating-point arithmetic:

| Metric | Deterministic definition | Output |
|---|---|---|
| acquisition | qualified anonymous entrances after bot and consent filters | count |
| activation | activated entrances / qualified entrances | integer basis points |
| conversion | first settled paid orders / activated entrances | integer basis points |
| paid net revenue | settled gross - refunds - chargebacks - tax - processor fees | integer JPY minor units |
| retention | retained actors / eligible closed cohort for the declared window | integer basis points |

Creator payouts are carried separately and are never deducted, hidden, or reclassified by the paid-net-revenue calculation. A zero denominator produces `nil` (not observable), never a fabricated zero. Ratios round half-up to the nearest basis point. Windows must be UTC with explicit start/end values; event and contract versions are mandatory.

Only aggregate rows are accepted. Direct identifiers, contact data, IP/device/session identifiers, cookies, tokens, secrets, and free text are forbidden. The dimension allow-list is `:all`, `:channel`, `:campaign`, `:country`, and `:device`; unknown or missing fields fail closed. Upstream systems must perform privacy-preserving deduplication before producing these aggregate counts.

Validate and calculate a fixture offline:

```clojure
(require '[clojure.edn :as edn]
         '[growth.metrics :as metrics])
(-> "test/fixtures/growth_metrics.edn"
    slurp
    edn/read-string
    metrics/calculate-fixture)
```

Before accepting a contract or fixture change, run every documented check:

```bash
clojure -M:dev:test
clojure -M:lint
clojure -M:dev:run
```

Changing a formula, event meaning, attribution/cohort window, rounding rule, currency unit, or privacy allow-list requires a new contract version and new golden fixtures. Never reinterpret historical rows under a newer version.

## 3. Governed revenue experiment ranking

`growth.experiment-ranking` ranks explicitly approved experiments without network, clock, randomness, floating-point inputs, writes, or actuation. Each candidate declares conversion lift and confidence in integer basis points, aggregate reach, value per conversion, cost, expected downside risk, and its MarketingGovernor decision. Candidates marked `:hold` or `:rejected` remain scoreable for audit transparency but are never rank-eligible.

Version 1 uses these formulas, all in JPY minor units:

```text
projected lift value = round(lift bps * reach * value per conversion / 10,000)
confidence-adjusted lift = round(projected lift value * confidence bps / 10,000)
expected value = confidence-adjusted lift - cost - downside risk
uncertainty = round(abs(projected lift value) * (10,000 - confidence bps) / 10,000)
lower bound = expected value - uncertainty - downside risk
upper bound = expected value + uncertainty
```

Signed rounding is half away from zero. Ranking sorts by expected value descending, lower bound descending, then printed experiment ID ascending, making ties reproducible. Every intermediate amount is returned under `:score/components`. Downside risk is an operator-supplied expected monetary loss; the lower bound includes it again as a conservative stress allowance.

```clojure
(require '[growth.experiment-ranking :as ranking])
(ranking/rank experiments)
```

Approval must come from the existing MarketingGovernor workflow; setting `:governance/decision :approved` is not itself an approval mechanism. Formula or input-semantic changes require a new ranking version and golden tests.

## 4. Revenue tenant onboarding and approval contract

`growth.tenant-onboarding` validates the closed, versioned EDN declaration in `test/fixtures/revenue_tenant_onboarding.edn`. It is an offline declaration only: validation performs no network calls, writes, clock reads, or credential provisioning. Never add credential values to this contract. `:service/credential-mode` must remain `:credential.mode/not-provisioned`, and only aggregate-metric reading, experiment drafting, and approval requesting may be declared.

Two distinct humans must approve: `:human.role/tenant-owner` owns the revenue data and audit record, while `:human.role/itonami-operator` operates the service and acts as audit custodian. An approved record requires an opaque actor ID and keyword evidence reference. The tenant owner cannot be replaced as audit owner by the service operator. Unknown fields, missing roles, duplicate human IDs, unapproved capabilities, credential-like keys, and approved records without evidence fail closed.

Operator procedure:

1. Copy the fixture and change only tenant identity, legal names, opaque human actor IDs, evidence references, and retention duration. Do not insert secrets, tokens, API keys, endpoints, or credentials.
2. Obtain independent approval from both declared human roles and record durable evidence references outside this repository.
3. Validate locally with `(growth.tenant-onboarding/validate! contract)`.
4. Run `clojure -M:dev:test` and `clojure -M:lint`. A valid declaration documents approval; it does not authorize or perform provisioning.
5. The tenant owner retains audit ownership; the itonami operator preserves and exports the audit record for the declared retention period. Rejection or validation failure stops onboarding.

## 5. Governed revenue-agent tick

`growth.revenue-agent/tick` composes the live-facts, tenant-onboarding, and experiment-ranking contracts into one bounded deterministic decision cycle. It accepts an already-fetched read-only aggregate fact snapshot, candidate experiments, and explicit human decisions. It returns ranked recommendations and append-only audit events; it has no publisher, payment, pricing, ad-platform, credential, network, clock, or Store-writing capability.

Run the checked-in example offline by loading `test/fixtures/revenue_agent_tick.edn` and `test/fixtures/revenue_tenant_onboarding.edn`, associating the onboarding map under `:onboarding`, then calling `growth.revenue-agent/tick`. Only decisions containing `:decision/status :approved`, a nonblank `:decision/by`, and a keyword `:decision/evidence-ref` are rank-eligible. Missing or malformed decisions fail closed to `:hold`; explicit rejection remains audited and excluded from ranking.

Before each production-adjacent tick, verify that both onboarding humans remain explicitly approved, all required advisory capabilities are declared, the fact tenant matches the onboarding tenant, and every fact fetch succeeded. Any invalid onboarding, capability mismatch, tenant mismatch, or live-fact error holds the entire tick. Never translate a ranked result into a financial write automatically: execution requires a separate human-operated system and authority outside this agent. Preserve every returned `:audit` event with its tick ID and approval evidence reference.

## 6. Current phase

This repo's `growth.phase/default-phase` is **0** (scaffold-only: no writes
at all, mock advisor, in-memory store). Do not change this default without a
new ADR — moving to Phase 1 requires the secrets-sharing decision and
tenant-onboarding extension described in `docs/business-model.md` and the
superproject ADR.

## 7. Production checklist (future, NOT current state)

Before any Phase 1 work starts:

- [ ] cross-repo secrets-sharing decision made and documented (itonami ↔
      club-shinshi's `DISPATCHER_INTERNAL_SECRET` / a scoped alternative)
- [ ] `growth.facts` implemented and reviewed (currently a stub only)
- [ ] cloud-itonami's tenant/external-onboarding model extended so
      club-shinshi can be registered as a real itonami tenant
- [ ] `clojure -M:dev:test` and `clojure -M:lint` still green
- [ ] a written data-flow diagram covering the new cross-repo read path
- [ ] incident-response contact agreed with club-shinshi's operator

Before any Phase 3 (auto-commit) work starts:

- [ ] Phase 1 and Phase 2 each independently validated in production for a
      meaningful observation window
- [ ] audit-ledger export reviewed by both parties
- [ ] the MarketingGovernor's dark-pattern keyword list reviewed and expanded
      based on real proposals seen in Phase 1/2 (the current list is a
      heuristic floor, not a complete classifier)

## 8. Responsibilities

- **itonami operator (Gftd Japan株式会社):** runs the actor, holds/rotates
  whatever credential Phase 1 ends up using, reviews the audit ledger,
  approves/rejects escalated proposals.
- **club-shinshi operator (JK株式会社):** owns the underlying metrics and
  the creator-payout terms; must sign off before `:ppv-terms-change` or any
  high-stakes proposal is approved, not just itonami's operator alone.

The MarketingGovernor's HOLD is not itonami's call to override — a hard
violation (unknown effect / dark-pattern / age-verification tamper) is
rejected regardless of who is asking.
