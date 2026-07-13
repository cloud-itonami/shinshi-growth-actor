(ns growth.aozora-test
  "Offline tests for `growth.aozora`'s real Publisher — every test injects a
  fake `:http-fn` (never the JDK `jvm-http-fn`) and fake `:json-write`/
  `:json-read` (`pr-str`/`clojure.edn/read-string`, this repo's own
  `growth.facts`/`growth.live` I/O-injection convention — no
  `clojure.data.json` dependency needed). No test in this namespace touches
  `pds.aozora.app` or any other network endpoint. Adapted from
  `shinshi.aozora-test` (`jk-luxury/club-shinshi/20-actors/shinshi`).

  IMPORTANT: fixture bodies use KEYWORD keys (`{:accessJwt ..}`, not
  `{\"accessJwt\" ..}`) — this simulates what `langchain.jvm/json-read`
  (jsonista's keyword-keys-object-mapper, this repo's REAL production
  `:json-read`) actually returns from parsing a real JSON response, not
  what `clojure.data.json/read-str` returns by default (string keys, which
  is what club-shinshi's own `shinshi.aozora` is injected with). An earlier
  version of both this file and `growth.aozora` itself used string-keyed
  fixtures + string-keyed `get` lookups — the tests passed, but
  `clojure -M:dev:run-live` failed for real every time (`createSession`
  always looked rejected even on a genuine HTTP 200, because
  `(get sbody \"accessJwt\")` against a keyword-keyed real response is
  always nil). Keep these keyword-keyed."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [growth.aozora :as aozora]
            [growth.cacao :as cacao]
            [growth.publisher :as publisher]))

(defn- fake-http
  "An http-fn that replays canned responses per URL suffix and records every
  request into `calls`."
  [calls responses]
  (fn [{:keys [url] :as req}]
    (swap! calls conj req)
    (or (some (fn [[suffix resp]]
                (when (str/ends-with? url suffix) resp))
              responses)
        (throw (ex-info "unexpected url (would have been a real network call)" {:url url})))))

(def ^:private io {:json-write pr-str :json-read edn/read-string})

(deftest publish-happy-path
  (let [id    (cacao/generate-identity)
        calls (atom [])
        http  (fake-http calls
                         {"createSession" {:status 200 :body (pr-str {:accessJwt "jwt-1"})}
                          "createRecord"  {:status 200 :body (pr-str
                                                              {:uri "at://did/coll/rkey" :cid "bafy1"})}})
        pub   (aozora/aozora-publisher (merge io {:identity id :http-fn http}))
        res   (publisher/publish! pub {:text "hello aozora"})]
    (testing "returns uri + cid from createRecord"
      (is (= {:uri "at://did/coll/rkey" :cid "bafy1"} res)))
    (testing "session then record, both against the default (real) PDS URL — but no actual network call was made"
      (is (= 2 (count @calls)))
      (is (= aozora/default-pds "https://pds.aozora.app"))
      (is (= (str aozora/default-pds "/xrpc/com.atproto.server.createSession")
             (:url (first @calls))))
      (is (= (str aozora/default-pds "/xrpc/com.atproto.repo.createRecord")
             (:url (second @calls)))))
    (testing "createSession body carries a self-minted CACAO (real signed token, never a hardcoded string)"
      (let [sess-body (edn/read-string (:body (first @calls)))]
        (is (string? (:cacao sess-body)))
        (is (pos? (count (:cacao sess-body))))
        (is (not= "mock-cacao-token" (:cacao sess-body)))))
    (testing "createRecord is JWT-authorized and shaped for the actor's own repo"
      (let [req  (second @calls)
            body (edn/read-string (:body req))]
        (is (= "Bearer jwt-1" (get-in req [:headers "Authorization"])))
        (is (= (:did id) (:repo body)))
        (is (= publisher/collection (:collection body)))
        (is (= "self" (:rkey body)))
        (is (= "hello aozora" (:text (:record body))))
        (is (= (:did id) (:actor (:record body))))
        (is (string? (:createdAt (:record body))))))))

(deftest publish-respects-record-collection-and-rkey
  (let [id    (cacao/generate-identity)
        calls (atom [])
        http  (fake-http calls
                         {"createSession" {:status 200 :body (pr-str {:accessJwt "jwt-1"})}
                          "createRecord"  {:status 200 :body (pr-str {:uri "u" :cid "c"})}})
        pub   (aozora/aozora-publisher (merge io {:identity id :http-fn http}))]
    (publisher/publish! pub {:text "t" :collection "com.example.other" :rkey "r1"})
    (let [body (edn/read-string (:body (second @calls)))]
      (is (= "com.example.other" (:collection body)))
      (is (= "r1" (:rkey body)))
      (is (nil? (:collection (:record body))) ":collection stripped from record")
      (is (nil? (:rkey (:record body))) ":rkey stripped from record"))))

(deftest publish-fails-on-session-error
  (let [id   (cacao/generate-identity)
        http (fake-http (atom [])
                        {"createSession" {:status 403 :body (pr-str {"error" "denied"})}})
        pub  (aozora/aozora-publisher (merge io {:identity id :http-fn http}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"createSession failed"
                          (publisher/publish! pub {:text "t"})))))

(deftest publish-fails-on-record-error
  (let [id   (cacao/generate-identity)
        http (fake-http (atom [])
                        {"createSession" {:status 200 :body (pr-str {:accessJwt "jwt-1"})}
                         "createRecord"  {:status 500 :body (pr-str {"error" "boom"})}})
        pub  (aozora/aozora-publisher (merge io {:identity id :http-fn http}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"createRecord failed"
                          (publisher/publish! pub {:text "t"})))))

(deftest publisher-requires-identity-and-json
  (is (thrown? AssertionError (aozora/aozora-publisher io)))
  (is (thrown? AssertionError (aozora/aozora-publisher (assoc io :identity {:did "did:key:z6Mk"}
                                                              :json-read nil))))
  (is (thrown? AssertionError (aozora/aozora-publisher (-> io
                                                            (assoc :identity {:did "did:key:z6Mk"})
                                                            (dissoc :json-write))))))
