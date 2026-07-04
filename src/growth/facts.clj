(ns growth.facts
  "Production data adapter STUB — NOT wired into `growth.operation` or
  `growth.sim` in this build. Phase 0 (this repo, as committed) never reads
  live club-shinshi data; `growth.store/seed-db`'s demo snapshot is the only
  data source.

  When Phase 1 (read-only live metrics) is built, this namespace would call
  club-shinshi's `ai-gftd-shinshi` internal D1 dispatch API — the same
  Cloudflare Worker route already implemented at
  `club-shinshi/60-apps/ai-gftd-project-shinshi/appview/ai-gftd-wasm-shinshi-sh1n5h1x/svelte/src/routes/_d1/+server.ts`
  (and its sibling `_metrics/revenue/+server.ts`) — using its existing
  operations:

    read_metric        {metric}                    → {value:int|null}
                        (allow-listed ad-telemetry column only)
    read_revenue_gate   {window_days?, end_day?}     → {ok, revenue:{creator-gmv-jpy, ...}}
                        (club-shinshi's own riskiest gate, ADR-2607021900:
                        creator-gmv-jpy exceeding ad-revenue-jpy)
    fetchExoPublisherStats(...)                       → ExoClick ad-network stats
                        (internal helper behind the same dispatch route, not
                        separately exposed)

  That route is internal-only: it is gated by an `x-internal-trust` header
  that must equal the deployment's `DISPATCHER_INTERNAL_SECRET` (see the
  route source above). Calling it from this actor would require:

    1. A cross-repo secrets-sharing decision — should itonami (this actor's
       operator, Gftd Japan株式会社) hold a copy of club-shinshi's
       (JK株式会社) `DISPATCHER_INTERNAL_SECRET`, or should club-shinshi
       stand up a scoped read-only token/route specifically for itonami's
       growth loop? Neither is decided; DO NOT hardcode or fetch any real
       secret to answer this here.
    2. Extending cloud-itonami's tenant/external-client-onboarding model
       (docs/adr/0009-open-business-registry-endpoint.md's noted gap: no
       durable per-client onboarding store yet) so club-shinshi can be
       registered as a real itonami tenant rather than an ad-hoc integration.

  Both are explicitly flagged as open follow-ups in the accompanying
  superproject ADR (`90-docs/adr/2607040900-shinshi-growth-actor-itonami-client-service.md`)
  and are NOT resolved by this commit. This file exists only so the shape of
  the eventual adapter is visible in code review; every function below is a
  stub that returns nil / throws rather than performing any network call."
  )

(defn read-metric
  "STUB — Phase 1 follow-up. Would POST {:op :read_metric :metric metric-key}
  to the club-shinshi internal dispatch route with an `x-internal-trust`
  header, once the secrets-sharing decision above is made. Always returns
  nil in this build; callers must fall back to `growth.store`'s demo seed."
  [_metric-key]
  nil)

(defn read-revenue-gate
  "STUB — Phase 1 follow-up. Would POST {:op :read_revenue_gate ...} to read
  club-shinshi's own riskiest gate (creator-gmv-jpy vs ad-revenue-jpy,
  ADR-2607021900). Always returns nil in this build."
  [& {:keys [_window-days _end-day]}]
  nil)

(defn hydrate!
  "STUB — Phase 1 follow-up. Would replace/seed a Store's hypotheses/metrics
  from live club-shinshi reads, falling back to whatever the store already
  holds (the demo seed) when the dispatch route is unreachable or the
  secrets-sharing decision is still pending — mirroring the annex-pointer
  fallback idiom other actors in this workspace use for m365-archive. NOT
  called anywhere in this build; `growth.store/seed-db` is always used
  instead."
  [st & [_opts]]
  st)
