(ns growth.murakumo
  "A `langchain.model/ChatModel` for itonami's own self-hosted inference
  gateway at `https://murakumo.cloud/api/v1/chat/completions` — OpenAI-
  compatible `/v1/chat/completions` shape, model id `qwen-agentworld-35b-a3b`.

  Reuses `langchain.model/openai-request-body` and
  `langchain.model/parse-openai-response` (pure functions, exposed by that
  namespace specifically for reuse/testing) instead of reimplementing
  OpenAI request/response handling. This namespace only adds what's
  actually murakumo-specific:

    - default `:url`/`:model`.
    - auth header `x-api-key: <token>` — NOT `authorization: Bearer`, which
      is what `langchain.model/openai-model` hardcodes; that's exactly why
      this can't just call `openai-model` directly and instead reifies its
      own `ChatModel`. The header is OPTIONAL: omitted entirely (never sent
      as an empty string) when no `:api-key` is supplied — murakumo's
      `/api/v1` gate is currently inert/token-less.
    - `:temperature` (default 0.8) and `:chat_template_kwargs
      {:enable_thinking false}` in the request body — murakumo's underlying
      reasoning model needs `enable_thinking: false` or it burns tokens on
      hidden chain-of-thought.
    - response post-processing: the model may still emit a
      `<think>...</think>` reasoning block inside the message content even
      with `enable_thinking: false` requested (defense in depth). That block
      is stripped (see `strip-think`, ported faithfully from
      `shinshi.worker.companion/strip-think`,
      `jk-luxury/club-shinshi/60-apps/ai-gftd-project-shinshi/appview/
      ai-gftd-wasm-shinshi-sh1n5h1x/cljs/src/shinshi/worker/companion.cljs`)
      before the text reaches `growth.growthllm/parse-proposal`, which does
      `edn/read-string` on it — a stray `<think>` block would break that
      parse.

  `.cljc` (portable), not `.clj` — like `langchain.model`'s own
  `openai-model`/`anthropic-model`, this needs no JVM-only crypto (unlike
  `growth.cacao`/`growth.aozora`, which are JVM-only for JDK-crypto reasons
  that don't apply here).

  WASM/host-injection premise, same contract as `langchain.model`/
  `growth.facts`: this namespace performs no I/O of its own accord.
  `:http-fn`/`:json-write`/`:json-read` must always be injected (throws at
  the `murakumo-model` call boundary otherwise) — never touch the network
  from a bare namespace-level call."
  (:require [clojure.string :as str]
            [langchain.model :as model]))

(def default-url
  "murakumo.cloud's OpenAI-compatible chat-completions endpoint."
  "https://murakumo.cloud/api/v1/chat/completions")

(def default-model "qwen-agentworld-35b-a3b")

(def default-temperature 0.8)

(def token-env-var
  "Env var holding murakumo's optional `x-api-key` token. Never hardcode the
  value; always resolve from the environment (see `read-token!`)."
  "MURAKUMO_PROXY_TOKEN")

;; ───────────────────────── portable env read ─────────────────────────
;; Mirrors `growth.facts/env` exactly (JVM `System/getenv` / Node `process.env`)
;; — kept as a local one-liner rather than a cross-domain require of
;; growth.facts (unrelated Phase 1 live-metrics namespace) for one fn.

(defn- env [name]
  #?(:clj  (System/getenv name)
     :cljs (some-> (.-env js/process) (aget name))))

(defn read-token!
  "Resolves MURAKUMO_PROXY_TOKEN once, at the call boundary (mirrors
  `growth.facts/read-secret!`'s naming/shape) — never re-read deep inside
  `-generate` on every call."
  []
  (env token-env-var))

;; ───────────────────────── <think> stripping ─────────────────────────

(defn strip-think
  "Remove any `<think>…</think>` chain-of-thought the murakumo reasoning
  model may emit even with `chat_template_kwargs {:enable_thinking false}`
  requested (defense in depth), so it never reaches
  `growth.growthllm/parse-proposal`'s `edn/read-string`. Also strips an
  UNTERMINATED `<think>` block (cut off mid-reasoning, e.g. hit
  `max_tokens`) to EOF, rather than leaving it dangling. Ported faithfully
  from `shinshi.worker.companion/strip-think`."
  [s]
  (-> (str s)
      ;; [\s\S] not . — cljs regex has no inline (?s) DOTALL flag.
      (str/replace #"<think>[\s\S]*?</think>" "")
      (str/replace #"<think>[\s\S]*$" "")   ; unterminated (hit max_tokens mid-think)
      str/trim))

;; ───────────────────────── ChatModel ─────────────────────────

(defn murakumo-model
  "The murakumo-backed ChatModel.

    (murakumo-model {:api-key    …   ; optional — omitted header when nil
                     :model      \"qwen-agentworld-35b-a3b\"
                     :temperature 0.8
                     :http-fn    host-fetch
                     :json-write … :json-read …})

  Mirrors `langchain.model/openai-model`'s shape/host-capability-guard
  convention (throws when `:http-fn`/`:json-write`/`:json-read` are missing
  — a host capability, never touched from a bare namespace-level call)."
  [{:keys [api-key model max-tokens temperature http-fn json-write json-read url]
    :or   {model       default-model
           url         default-url
           temperature default-temperature
           #?@(:cljs [json-write (fn [m] (js/JSON.stringify (clj->js m)))
                      json-read  (fn [s] (js->clj (js/JSON.parse s) :keywordize-keys true))])}}]
  (when-not http-fn
    (throw (ex-info ":http-fn must be injected (host capability)" {})))
  (when-not (and json-write json-read)
    (throw (ex-info ":json-write/:json-read must be injected on this host" {})))
  (reify model/ChatModel
    (-generate [_ messages opts]
      (let [body (-> (model/openai-request-body json-write messages
                                                 (merge {:model model :max-tokens max-tokens} opts))
                      (assoc :temperature (get opts :temperature temperature))
                      (assoc :chat_template_kwargs {:enable_thinking false}))
            {:keys [status] resp-body :body}
            (http-fn {:url url
                      :method :post
                      :headers (cond-> {"content-type" "application/json"}
                                 api-key (assoc "x-api-key" api-key))
                      :body (json-write body)})]
        (when-not (and status (<= 200 status 299))
          (throw (ex-info "murakumo API error" {:status status :body resp-body})))
        (-> (model/parse-openai-response json-read (json-read resp-body))
            (update :content strip-think))))))
