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

## 2. Current phase

This repo's `growth.phase/default-phase` is **0** (scaffold-only: no writes
at all, mock advisor, in-memory store). Do not change this default without a
new ADR — moving to Phase 1 requires the secrets-sharing decision and
tenant-onboarding extension described in `docs/business-model.md` and the
superproject ADR.

## 3. Production checklist (future, NOT current state)

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

## 4. Responsibilities

- **itonami operator (Gftd Japan株式会社):** runs the actor, holds/rotates
  whatever credential Phase 1 ends up using, reviews the audit ledger,
  approves/rejects escalated proposals.
- **club-shinshi operator (JK株式会社):** owns the underlying metrics and
  the creator-payout terms; must sign off before `:ppv-terms-change` or any
  high-stakes proposal is approved, not just itonami's operator alone.

The MarketingGovernor's HOLD is not itonami's call to override — a hard
violation (unknown effect / dark-pattern / age-verification tamper) is
rejected regardless of who is asking.
