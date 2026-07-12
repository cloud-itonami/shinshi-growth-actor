(ns growth.publish-test
  "Contract tests for the `:commit` node's new publish-on-commit wiring
  (`growth.operation/build`'s `:publisher` opt). Uses a fake `Publisher`
  (records what was \"published\" in an atom, like `growth.publisher/
  MockPublisher` already does) — no test here reaches any real network
  endpoint. Mirrors `growth.contract-test`'s `fixed-advisor`/`exec` idiom."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [growth.store :as store]
            [growth.growthllm :as growthllm]
            [growth.publisher :as publisher]
            [growth.operation :as op]))

(defn- fixed-advisor [proposal]
  (reify growthllm/Advisor (-advise [_ _ _] proposal)))

(defn- exec [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(def ^:private phase3-ctx {:actor-id "growth-llm" :phase 3})

(defn- recording-publisher
  "A fake Publisher: conj's every published record into `calls`, returns a
  deterministic {:uri :cid}."
  [calls]
  (reify publisher/Publisher
    (publish! [_ record]
      (swap! calls conj record)
      {:uri (str "at://fake/" (:text record)) :cid (str "fakecid:" (:text record))})))

(defn- failing-publisher [calls]
  (reify publisher/Publisher
    (publish! [_ record]
      (swap! calls conj record)
      (throw (ex-info "simulated publish failure" {:reason :test})))))

;; ───────────────────────── (a) marketing-copy / creator-outreach publish exactly once ─────────────────────────

(deftest marketing-copy-clean-commit-publishes-exactly-once
  (testing "a clean, high-confidence :marketing-copy proposal that reaches :commit at Phase 3 triggers exactly one publish! with the right record"
    (let [db    (store/seed-db)
          calls (atom [])
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "会員限定キャンペーンのお知らせ"
                                         :rationale "実測に基づくクリーンな訴求"
                                         :cites [:H1] :effect :marketing-copy :confidence 0.95})
                              :publisher (recording-publisher calls)})
          res   (exec actor "mc1" {:op :marketing-copy} phase3-ctx)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= 1 (count @calls)) "publish! called exactly once")
      (is (= {:text "会員限定キャンペーンのお知らせ" :collection publisher/collection}
             (first @calls)))
      (let [published (last (store/ledger db))]
        (is (= :growth.audit/published (:t published)))
        (is (= :marketing-copy (:op published)))
        (is (= "at://fake/会員限定キャンペーンのお知らせ" (:uri published)))
        (is (= "fakecid:会員限定キャンペーンのお知らせ" (:cid published)))))))

(deftest creator-outreach-clean-commit-publishes-exactly-once
  (testing "a clean, high-confidence :creator-outreach proposal that reaches :commit at Phase 3 triggers exactly one publish! with the right record"
    (let [db    (store/seed-db)
          calls (atom [])
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "クリエイター向け説明会のお知らせ"
                                         :rationale "実測に基づくクリーンな訴求"
                                         :cites [:H1] :effect :creator-outreach :confidence 0.9})
                              :publisher (recording-publisher calls)})
          res   (exec actor "co1" {:op :creator-outreach} phase3-ctx)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= 1 (count @calls)))
      (is (= {:text "クリエイター向け説明会のお知らせ" :collection publisher/collection}
             (first @calls)))
      (is (= :growth.audit/published (:t (last (store/ledger db))))))))

;; ───────────────────────── (b) content-experiment does NOT publish ─────────────────────────

(deftest content-experiment-clean-commit-does-not-publish
  (testing ":content-experiment changes product content, not an announcement — never calls publish!"
    (let [db    (store/seed-db)
          calls (atom [])
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "clean" :rationale "clean"
                                         :cites [:H1] :effect :content-experiment
                                         :confidence 0.9})
                              :publisher (recording-publisher calls)})
          res   (exec actor "ce1" {:op :content-experiment} phase3-ctx)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= 0 (count @calls)) "publish! never called for :content-experiment")
      (is (not-any? #(= :growth.audit/published (:t %)) (store/ledger db))))))

;; ───────────────────────── publish failure is caught, never thrown out of :commit ─────────────────────────

(deftest publish-failure-is-caught-and-recorded-not-thrown
  (testing "a Publisher that throws does not crash :commit; a publish-failed audit fact is recorded instead"
    (let [db    (store/seed-db)
          calls (atom [])
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "clean" :rationale "clean"
                                         :cites [:H1] :effect :marketing-copy :confidence 0.9})
                              :publisher (failing-publisher calls)})
          res   (exec actor "mc2" {:op :marketing-copy} phase3-ctx)]
      (is (= :commit (get-in res [:state :disposition]))
          ":commit still completes — SSoT + effect-committed fact land regardless of publish outcome")
      (is (= 1 (count @calls)) "publish! was attempted")
      (let [failed (last (store/ledger db))]
        (is (= :growth.audit/publish-failed (:t failed)))
        (is (= :marketing-copy (:op failed)))
        (is (string? (:error failed)))))))

;; ───────────────────────── (c) default op/build (no :publisher) — zero regression ─────────────────────────

(deftest default-build-without-publisher-still-commits-content-experiment-unchanged
  (testing "op/build with no :publisher opt behaves exactly as before this change for :content-experiment"
    (let [db    (store/seed-db)
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "clean" :rationale "clean"
                                         :cites [:H1] :effect :content-experiment
                                         :confidence 0.9})})
          res   (exec actor "def1" {:op :content-experiment} phase3-ctx)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= 1 (count (store/ledger db))) "no extra ledger fact — publish path never engaged")
      (is (= :growth.effect/committed (:t (first (store/ledger db))))))))

(deftest default-build-without-publisher-uses-mock-publisher-for-marketing-copy
  (testing "op/build with no :publisher opt still publishes :marketing-copy via the default mock-publisher (never throws, never touches the network)"
    (let [db    (store/seed-db)
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "clean" :rationale "clean"
                                         :cites [:H1] :effect :marketing-copy :confidence 0.9})})
          res   (exec actor "def2" {:op :marketing-copy} phase3-ctx)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= 2 (count (store/ledger db)))
          "effect-committed + published facts, both via the default mock-publisher")
      (is (= :growth.audit/published (:t (last (store/ledger db))))))))
