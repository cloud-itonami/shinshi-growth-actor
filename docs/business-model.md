# Business Model: shinshi-growth-actor

This repository is a **service actor**, not an open business blueprint in its
own right (contrast `gftd-talent-actor`, which publishes itself as a forkable
OSS business). It exists to run itonami's growth loop *for* one specific
client.

## The relationship

| Party | Legal entity | Role |
|---|---|---|
| Operator | Gftd Japan株式会社 | Runs itonami (`cloud-itonami`), operates this actor |
| Client | JK株式会社 | Operates club-shinshi (`shinshi.club`), receives the growth-loop service |

Both are separate legal entities per the superproject's
`90-docs/adr/2607021500-portfolio-seven-layer-business-model-lean-canvas.md`.
This is a **new** client relationship: at the time this repo was created,
neither `cloud-itonami` nor `club-shinshi` referenced the other. There is no
prior shared infrastructure, shared secrets, or shared tenant model to build
on — Phase 1+ will need to create that (see Open Follow-ups below).

## What "growth loop as a service" means here

itonami's growth-LLM proposes growth-loop experiments against club-shinshi's
own BMC hypothesis backlog
(`ai-gftd-shinshi/docs/260613-bmc-lean.datoms.edn`, riskiest-first: H1 organic
pageview growth, H2 ad CM>0) and its own riskiest product gate — **creator
GMV exceeding ad revenue**
(`90-docs/adr/2607021900-portfolio-add-isekai-club-shinshi.md`). Every
proposal is one of:

- `:content-experiment` — low-stakes, eligible for auto-commit at Phase 3
- `:pricing-experiment` — high-stakes, always human-approved
- `:ad-spend-change` — high-stakes, always human-approved
- `:creator-outreach` — low-stakes, eligible for auto-commit at Phase 3
- `:ppv-terms-change` — high-stakes AND always implies a creator-payout
  review, always human-approved regardless of confidence
- `:marketing-copy` — low-stakes, eligible for auto-commit at Phase 3, but
  hard-rejected outright if it reads as a dark pattern

No proposal is ever allowed to touch age-verification or consent copy — that
is a hard, un-overridable rejection (ExoClick / adult-content compliance).

## Offer (once past Phase 0)

- governed growth experimentation on club-shinshi's behalf, with every
  proposal traceable to a BMC hypothesis id and a governor verdict
- an immutable audit ledger of every proposal, approval, hold and commit
- a charter-clean floor: no dark patterns, no hidden fees, no misleading
  auto-renew language, ever, in any auto-generated marketing copy
- creator-payout protection: nothing that touches the creator GMV split ever
  auto-commits, no matter how confident the model is

## Open follow-ups (NOT resolved by this repo)

1. **cloud-itonami tenant/external-onboarding gap.** `cloud-itonami`'s own
   `docs/adr/0009-open-business-registry-endpoint.md` already flags that no
   durable per-client onboarding store exists yet. Making club-shinshi a
   *real* itonami-hosted tenant (rather than an ad-hoc actor repo) needs that
   built first. This repo does not attempt to solve it.
2. **Secrets-sharing decision for live metrics.** club-shinshi's own
   `ai-gftd-shinshi` internal D1 dispatch API (`read_metric`,
   `read_revenue_gate`, ExoClick publisher stats) is gated by an
   `x-internal-trust` header checked against `DISPATCHER_INTERNAL_SECRET`.
   Whether itonami holds a copy of that secret, or club-shinshi stands up a
   scoped read-only credential for this actor specifically, is undecided —
   see `src/growth/facts.clj`. Phase 1 cannot start until this is resolved.

## Non-Negotiables

- Do not commit real club-shinshi metrics or secrets to this repository.
- Do not bypass the MarketingGovernor for any write.
- Do not wire `growth.facts` into `growth.operation` until the
  secrets-sharing decision above is made and the corresponding ADR is
  amended/superseded.
