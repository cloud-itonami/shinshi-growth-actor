(ns growth.publisher
  "Publisher — the outbound surface for a shinshi-growth-actor social-post
  announcement, injected so the network is a swap (`MockPublisher` default ‖
  real app-aozora createRecord via `growth.aozora`). The caller never reaches
  the network directly; commit paths call `(publish! publisher record)` only.

  Publishing a growth-LLM-drafted, MarketingGovernor-cleared social-post
  announcement (e.g. `:marketing-copy` / `:creator-outreach`) is this actor's
  own SPEECH — NOT actuation on club-shinshi's systems. See
  `growth.operation`'s `:commit` node for where this is called. Ported shape
  from `shinshi.publisher` (`jk-luxury/club-shinshi/20-actors/shinshi`, keep
  in sync).

  record shape (what gets published):
    {:text (social-post body)
     :collection \"com.gftdcojp.apps.itonami.growthPost\"}")

(def collection
  "itonami's growthPost collection on app-aozora — this actor's own
  auto-generated marketing-copy / creator-outreach announcements published on
  club-shinshi's behalf (ADR-2607040900)."
  "com.gftdcojp.apps.itonami.growthPost")

(defprotocol Publisher
  (publish! [p record] "publish one record → {:uri :cid}"))

(defrecord MockPublisher [a]
  Publisher
  (publish! [_ record]
    (swap! a conj record)
    {:uri (str "at://mock/growth/" (or (:text record) "unknown"))
     :cid (str "mock:" (or (:text record) "unknown"))}))

(defn mock-publisher
  "Deterministic in-memory publisher (default — records would-be posts).
  Optional atom arg lets a test read back what would have been published."
  ([] (->MockPublisher (atom [])))
  ([a] (->MockPublisher a)))
