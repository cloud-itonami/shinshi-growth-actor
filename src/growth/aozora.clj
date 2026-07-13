(ns growth.aozora
  "Real app-aozora Publisher for shinshi-growth-actor — creates a record in
  the `growth.publisher/collection` (com.gftdcojp.apps.itonami.growthPost)
  collection on an aozora PDS via the AT Protocol
  com.atproto.repo.createRecord XRPC, authenticated by a depth-1 self-minted
  CACAO (this actor's own did:key + a revocable member CACAO leash = the
  off-switch). Ported shape from `shinshi.aozora`
  (`jk-luxury/club-shinshi/20-actors/shinshi`, keep in sync).

  I/O is injected: an http-fn (default JDK java.net.http, no dependency) and a
  JSON pair passed by the caller, so this namespace stays dependency-free.
  Publication is the actor's own SPEECH — NOT actuation.

  JVM-only I/O namespace by convention (.clj); see `growth.cacao`'s docstring
  for the kototama/cljs porting note.

  `:json-read` must return KEYWORD keys (e.g. `langchain.jvm/json-read`,
  jsonista's `keyword-keys-object-mapper` — this repo's actual convention,
  used everywhere else here: `growth.facts`, `growth.murakumo`, `growth.live`).
  This differs from the reference `shinshi.aozora` this was ported from,
  which is injected `clojure.data.json/read-str` (STRING keys by default) by
  its own caller — a real, found-in-production bug in an earlier version of
  this file used `(get sbody \"accessJwt\")` (string-keyed) against a
  keyword-keyed `json-read`, so `accessJwt`/`uri`/`cid` extraction silently
  returned nil and `createSession` always looked like it failed even on a
  real HTTP 200 (found running `clojure -M:dev:run-live` for real — every
  manual `createSession` retry outside this fn succeeded at 200, but
  `publish!` itself always threw \"aozora createSession failed\")."
  (:require [clojure.string :as str]
            [growth.cacao :as cacao]
            [growth.publisher :as publisher])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Instant]
           [java.util UUID]))

(def default-pds "https://pds.aozora.app")

(defn jvm-http-fn
  "host-caps :http-fn backed by the JDK HTTP client (no dependency)."
  [{:keys [url method headers body]}]
  (let [b (HttpRequest/newBuilder (URI/create url))]
    (doseq [[k v] headers] (.header b k v))
    (let [req  (-> b (.method (str/upper-case (name (or method :post)))
                             (if body
                               (HttpRequest$BodyPublishers/ofString body)
                               (HttpRequest$BodyPublishers/noBody)))
                   (.build))
          resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)})))

(defn aozora-publisher
  "Returns a `growth.publisher/Publisher` that creates growthPost records on
  the aozora PDS. opts:
    :pds         PDS base URL (default default-pds)
    :identity    {:private-key :did …} from cacao/load-or-create-identity!
    :leash       a member CACAO b64 (the revocable off-switch); nil → record
                 attributed to the actor's own did:key (depth-1 self-mint)
    :json-write  :json-read  injected JSON fns — :json-read MUST return
                 keyword keys (e.g. `langchain.jvm/json-read`; NOT
                 `clojure.data.json/read-str` without `:key-fn keyword` —
                 see ns docstring)
    :http-fn     optional override (default jvm-http-fn)"
  [{:keys [pds identity json-write json-read http-fn]
    :or   {pds default-pds http-fn jvm-http-fn}}]
  (assert (:did identity) ":identity with :did is required (cacao/load-or-create-identity!)")
  (assert json-write ":json-write fn is required (e.g. clojure.data.json/write-str)")
  (assert json-read  ":json-read fn is required (e.g. clojure.data.json/read-str)")
  (reify publisher/Publisher
    (publish! [_ record]
      ;; app-aozora-pds auth (self-sovereign CACAO): mint a CACAO for the
      ;; actor's OWN did:key, exchange it at createSession for an HS256 session
      ;; JWT, then createRecord with that JWT — the PDS enforces session DID ==
      ;; repo DID, so the repo is addressed by the actor's did:key. (The old
      ;; CACAO-Bearer-at-createRecord model returned 403 on this PDS.)
      (let [now   (str (Instant/now))
            graph (cacao/canonical-graph (:did identity) cacao/default-db-name)
            cacao (cacao/mint identity
                              {:cap :cap/transact :scope graph}
                              {:aud pds :nonce (str (UUID/randomUUID))
                               :issued-at now
                               :expiry (str (.plusSeconds (Instant/now) 3600))})
            sess  (http-fn {:url     (str pds "/xrpc/com.atproto.server.createSession")
                            :method  :post
                            :headers {"Content-Type" "application/json"}
                            :body    (json-write {:cacao cacao})})
            sbody (json-read (:body sess))
            jwt   (:accessJwt sbody)]
        (when-not (and (= 200 (:status sess)) jwt)
          (throw (ex-info "aozora createSession failed"
                          {:status (:status sess) :body (:body sess)})))
        (let [coll  (or (:collection record) publisher/collection)
              rec   (-> (dissoc record :rkey :collection)
                        (assoc :createdAt now :actor (:did identity)))
              resp  (http-fn {:url     (str pds "/xrpc/com.atproto.repo.createRecord")
                              :method  :post
                              :headers {"Content-Type" "application/json"
                                        "Authorization" (str "Bearer " jwt)}
                              :body    (json-write {:repo       (:did identity)
                                                    :collection coll
                                                    :rkey       (or (:rkey record) "self")
                                                    :record     rec})})
              rbody (json-read (:body resp))]
          (when-not (= 200 (:status resp))
            (throw (ex-info "aozora createRecord failed"
                            {:status (:status resp) :body (:body resp)})))
          {:uri (:uri rbody) :cid (:cid rbody)})))))
