(ns growth.store
  "SSoT for the shinshi-growth actor, behind a `Store` protocol so the backend
  is a swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. The deterministic default
                       for dev/tests/demo (no deps).
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible EAV
                       store (datalog q / pull / ref attrs / upsert). Pure
                       `.cljc`, so it runs offline AND can be pointed at a
                       real Datomic Local or a kotoba-server pod by swapping
                       `langchain.db`'s `:db-api` (see `langchain.kotoba-db`).

  Both implement the same protocol and pass the same contract
  (test/growth/contract_test.cljc), which is the whole point: the actor, the
  MarketingGovernor and the audit ledger never know which SSoT they run on.

  Domain: this actor runs the growth loop itonami (operated by Gftd Japan
  株式会社, `cloud-itonami` repo) provides *as a service* to club-shinshi
  (operated by JK株式会社, a separate legal entity — ADR-2607021500). It is a
  NEW client relationship; there is no prior integration between the two.
  Every record this actor produces carries `:growth.tenant/id \"club-shinshi\"`
  so a future multi-tenant itonami deployment can scope reads/writes per
  client — this is a schema-level tag only, NOT a full tenant registry (that
  is cloud-itonami ADR-0009's open external-onboarding gap; deliberately left
  unsolved here, see the accompanying superproject ADR).

  Ledger facts are namespaced:
    :growth.proposal/*  — growth-LLM proposals (advisory, pre-governor)
    :growth.decision/*  — MarketingGovernor verdicts
    :growth.effect/*    — committed experiment/effect records
    :growth.audit/*     — everything else (approval requests/grants, holds)

  The ledger stays append-only on every backend — 'who proposed/approved
  what growth change, on what BMC-hypothesis basis' is always a query over an
  immutable log."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]
            [langchain.db :as d]))

(def tenant-id
  "This actor serves exactly one client tenant in Phase 0. A real multi-tenant
  itonami deployment would parameterize this; deferred to the ADR-0009
  external-onboarding follow-up."
  "club-shinshi")

(defprotocol Store
  (hypothesis [s id] "one BMC hypothesis backlog entry ({:id :risk :status :gate}), or nil")
  (all-hypotheses [s])
  (metric [s k] "latest known value for a metric key, or nil (demo/cached only in Phase 0)")
  (experiment [s id] "a committed experiment/effect record, or nil")
  (all-experiments [s])
  (ledger [s])
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-hypotheses [s hyps]  "replace/seed the hypothesis backlog (map id→hyp)")
  (with-metrics [s metrics]  "replace/seed metrics (map key→value)"))

;; ───────────────────────── demo data ─────────────────────────

(defn demo-data
  "A small, self-contained BMC snapshot so the actor + tests run offline. The
  hypothesis ids mirror club-shinshi's own backlog
  (`ai-gftd-shinshi/docs/260613-bmc-lean.datoms.edn`, H1/H2 riskiest-first):
  H1 organic pageview growth, H2 ad CM>0. In prod, Phase 1 would hydrate this
  from `ai-gftd-shinshi`'s internal D1 dispatch API instead (see
  `growth.facts`, NOT implemented in this build)."
  []
  {:hypotheses
   {:H1 {:id :H1 :risk :riskiest :status :untested
         :gate "organic pageviews MoM ≥ +20% を3ヶ月連続 ∧ AT firehose 帰属 new session ≥ 15%"}
    :H2 {:id :H2 :risk :riskiest :status :untested
         :gate "CM > 0、評価窓 90日・週次 eCPM/fill 観測"}}
   :metrics
   {:organic-pageviews-mom-pct 4.0
    :ad-revenue-jpy            120000
    :creator-gmv-jpy           0}}) ; club-shinshi's own riskiest gate (ADR-2607021900): creator-gmv-jpy must eventually exceed ad-revenue-jpy

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (hypothesis [_ id] (get-in @a [:hypotheses id]))
  (all-hypotheses [_] (sort-by :id (vals (:hypotheses @a))))
  (metric [_ k] (get-in @a [:metrics k]))
  (experiment [_ id] (get-in @a [:experiments id]))
  (all-experiments [_] (sort-by :id (vals (:experiments @a))))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect path payload]}]
    (when (and effect path)
      (swap! a update-in [:experiments (first path)]
             merge (assoc payload :id (first path) :effect effect :growth.tenant/id tenant-id)))
    s)
  (append-ledger! [_ fact]
    (let [f (assoc fact :growth.tenant/id tenant-id)]
      (swap! a update :ledger conj f)
      f))
  (with-hypotheses [s hyps]    (when (seq hyps)    (swap! a assoc :hypotheses hyps)) s)
  (with-metrics [s metrics]    (when (seq metrics) (swap! a assoc :metrics metrics)) s))

(defn seed-db
  "A MemStore seeded with the demo BMC snapshot. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :experiments {} :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────────────

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (payloads, ledger facts) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities."
  {:hyp/id        {:db/unique :db.unique/identity}
   :metric/key    {:db/unique :db.unique/identity}
   :experiment/id {:db/unique :db.unique/identity}
   :ledger/seq    {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- hyp->tx [{:keys [id risk status gate]}]
  {:hyp/id id :hyp/risk risk :hyp/status status :hyp/gate gate})

(defn- pull->hyp [m]
  (when (:hyp/id m)
    {:id (:hyp/id m) :risk (:hyp/risk m) :status (:hyp/status m) :gate (:hyp/gate m)}))

(defrecord DatomicStore [conn]
  Store
  (hypothesis [_ id]
    (pull->hyp (d/pull (d/db conn) [:hyp/id :hyp/risk :hyp/status :hyp/gate] [:hyp/id id])))
  (all-hypotheses [_]
    (->> (d/q '[:find [?id ...] :where [?e :hyp/id ?id]] (d/db conn))
         (map #(pull->hyp (d/pull (d/db conn) [:hyp/id :hyp/risk :hyp/status :hyp/gate] [:hyp/id %])))
         (sort-by :id)))
  (metric [_ k]
    (d/q '[:find ?v . :in $ ?k :where [?e :metric/key ?k] [?e :metric/value ?v]]
         (d/db conn) k))
  (experiment [_ id]
    (dec* (d/q '[:find ?p . :in $ ?id :where [?e :experiment/id ?id] [?e :experiment/payload ?p]]
                (d/db conn) id)))
  (all-experiments [_]
    (->> (d/q '[:find [?id ...] :where [?e :experiment/id ?id]] (d/db conn))
         (map #(dec* (d/q '[:find ?p . :in $ ?id :where [?e :experiment/id ?id] [?e :experiment/payload ?p]]
                            (d/db conn) %)))
         (sort-by :id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (commit-record! [s {:keys [effect path payload]}]
    (when (and effect path)
      (d/transact! conn [{:experiment/id (first path)
                          :experiment/payload (enc (assoc payload :id (first path) :effect effect
                                                          :growth.tenant/id tenant-id))}]))
    s)
  (append-ledger! [s fact]
    (let [f (assoc fact :growth.tenant/id tenant-id)]
      (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc f)}])
      f))
  (with-hypotheses [s hyps]
    (when (seq hyps) (d/transact! conn (mapv hyp->tx (vals hyps)))) s)
  (with-metrics [s metrics]
    (when (seq metrics)
      (d/transact! conn (vec (for [[k v] metrics] {:metric/key k :metric/value v}))))
    s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:hypotheses .. :metrics ..}); empty when omitted. PERSIST is the optional
  sealed transaction append/read port; queries remain local."
  ([] (datomic-store {} nil))
  ([data] (datomic-store data nil))
  ([{:keys [hypotheses metrics]} persist]
   (let [s (->DatomicStore (d/create-conn schema persist))]
     (-> s (with-hypotheses hypotheses) (with-metrics metrics)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo BMC snapshot — the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  "Human-readable one-liner for a ledger fact (used by the demo)."
  [{:keys [t disposition effect basis]}]
  (str/join " · "
            (remove nil?
                    [(some-> disposition name)
                     (str "t=" (name (or t :unknown)))
                     (when effect (str "effect=" (name effect)))
                     (when basis (str "basis=" (pr-str basis)))])))
