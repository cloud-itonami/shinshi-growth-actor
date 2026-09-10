(ns growth.murakumo-test
  "Offline tests for `growth.murakumo`'s murakumo.cloud ChatModel — every
  test injects a FAKE `:http-fn` (mirroring the fake-http-fn convention
  already used in `growth.facts-test`/`growth.aozora-test`), never a real
  network call. `:json-write`/`:json-read` are `pr-str`/`clojure.edn/
  read-string` (same convention), so the fake `http-fn` can pattern-match
  on EDN request/response bodies without a real JSON lib dependency."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [langchain.model :as model]
            [growth.murakumo :as murakumo]))

(def ^:private io {:json-write pr-str :json-read edn/read-string})

(defn- openai-response
  "A canned OpenAI-compatible chat-completions response body carrying
  `content` as the assistant message's text."
  [content]
  (pr-str {:choices [{:message {:content content} :finish_reason "stop"}]
           :usage {:total_tokens 42}}))

(defn- capturing-http-fn
  "Records every request into `calls`, always replies with `openai-response`
  of `content`."
  [calls content]
  (fn [req]
    (swap! calls conj req)
    {:status 200 :body (openai-response content)}))

;; ───────────────────────── normal round trip + murakumo-specific fields ─────────────────────────

(deftest murakumo-model-round-trips-response-and-sends-murakumo-specific-request-fields
  (testing "a normal response round-trips to {:role :assistant :content ...}"
    (let [calls (atom [])
          http  (capturing-http-fn calls "hello from murakumo")
          m     (murakumo/murakumo-model (merge io {:http-fn http}))
          resp  (model/-generate m [{:role :user :content "hi"}] {})]
      (is (= :assistant (:role resp)))
      (is (= "hello from murakumo" (:content resp)))))
  (testing "the murakumo-specific request fields are actually present in what was sent"
    (let [calls (atom [])
          http  (capturing-http-fn calls "ok")
          m     (murakumo/murakumo-model (merge io {:http-fn http}))]
      (model/-generate m [{:role :user :content "hi"}] {})
      (is (= 1 (count @calls)))
      (let [req  (first @calls)
            body (edn/read-string (:body req))]
        (is (= murakumo/default-url (:url req)))
        (is (= murakumo/default-model (:model body)))
        (is (= murakumo/default-temperature (:temperature body)))
        (is (= {:enable_thinking false} (:chat_template_kwargs body)))))))

;; ───────────────────────── <think> stripping ─────────────────────────

(deftest murakumo-model-strips-terminated-think-block-from-content
  (let [calls (atom [])
        http  (capturing-http-fn calls "<think>let me reason about this...</think>final answer")
        m     (murakumo/murakumo-model (merge io {:http-fn http}))
        resp  (model/-generate m [{:role :user :content "hi"}] {})]
    (is (= "final answer" (:content resp)))))

(deftest murakumo-model-strips-unterminated-think-block-to-eof
  (testing "a <think> block cut off mid-reasoning (e.g. hit max_tokens) is stripped to EOF, not left dangling"
    (let [calls (atom [])
          http  (capturing-http-fn calls "<think>reasoning that never closes because max_tokens was hit")
          m     (murakumo/murakumo-model (merge io {:http-fn http}))
          resp  (model/-generate m [{:role :user :content "hi"}] {})]
      (is (= "" (:content resp)))
      (is (not (re-find #"<think>" (:content resp)))))))

(deftest strip-think-unit
  (testing "terminated block removed (no internal-whitespace collapse, matching the ported regex verbatim)"
    (is (= "before  after" (murakumo/strip-think "before <think>hidden</think> after"))))
  (testing "unterminated block stripped to EOF"
    (is (= "before" (murakumo/strip-think "before <think>hidden and cut off"))))
  (testing "no think block: unchanged (trimmed)"
    (is (= "plain text" (murakumo/strip-think "plain text")))))

;; ───────────────────────── x-api-key header optionality ─────────────────────────

(deftest murakumo-model-x-api-key-header-present-when-api-key-given
  (let [calls (atom [])
        http  (capturing-http-fn calls "ok")
        m     (murakumo/murakumo-model (merge io {:http-fn http :api-key "secret-token-123"}))]
    (model/-generate m [{:role :user :content "hi"}] {})
    (is (= "secret-token-123" (get (:headers (first @calls)) "x-api-key")))))

(deftest murakumo-model-x-api-key-header-absent-not-empty-when-omitted
  (let [calls (atom [])
        http  (capturing-http-fn calls "ok")
        m     (murakumo/murakumo-model (merge io {:http-fn http}))]
    (model/-generate m [{:role :user :content "hi"}] {})
    (is (not (contains? (:headers (first @calls)) "x-api-key"))
        "no x-api-key header at all — not sent as an empty string")))

;; ───────────────────────── host-capability guard ─────────────────────────

(deftest murakumo-model-requires-injected-io
  (testing "missing everything throws"
    (is (thrown? #?(:clj Exception :cljs js/Error) (murakumo/murakumo-model {}))))
  (testing "missing json-write/json-read (http-fn alone) throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (murakumo/murakumo-model {:http-fn (fn [_] {:status 200 :body "{}"})}))))
  (testing "missing http-fn (json-write/json-read alone) throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (murakumo/murakumo-model io)))))
