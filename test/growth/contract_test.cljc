(ns growth.contract-test
  "The governor contract as executable tests — the growth-loop analog of
  talent's policy-contract-test / itonami's governor tests. The single
  invariant under test:

    growth-LLM never writes a record the MarketingGovernor would reject,
    and every decision (commit OR hold) leaves exactly one ledger fact.

  Also proves `MemStore ≡ DatomicStore` (same contract, different backend) —
  the whole point of `growth.store`'s Store protocol."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [langchain.edn-persist :as edn-persist]
            [growth.store :as store]
            [growth.growthllm :as growthllm]
            [growth.operation :as op])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ───────────────────────── Store contract: MemStore ≡ DatomicStore ─────────────────────────

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest store-read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= :riskiest (:risk (store/hypothesis s :H1))))
      (is (= :untested (:status (store/hypothesis s :H1))))
      (is (= 2 (count (store/all-hypotheses s))))
      (is (= 120000 (store/metric s :ad-revenue-jpy)))
      (is (= 0 (store/metric s :creator-gmv-jpy))
          "club-shinshi's own riskiest gate starting point (ADR-2607021900)"))))

(deftest store-write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (store/commit-record! s {:effect :content-experiment :path ["exp-1"]
                                :payload {:summary "test" :rationale "r" :cites [:H1]}})
      (is (= :content-experiment (:effect (store/experiment s "exp-1"))))
      (is (= "test" (:summary (store/experiment s "exp-1"))))
      (is (= "club-shinshi" (:growth.tenant/id (store/experiment s "exp-1")))
          "every committed record carries the tenant tag")
      (store/append-ledger! s {:t :a :disposition :commit})
      (store/append-ledger! s {:t :b :disposition :hold})
      (is (= [:commit :hold] (mapv :disposition (store/ledger s))))
      (is (every? #(= "club-shinshi" (:growth.tenant/id %)) (store/ledger s))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/hypothesis s :H1)))
    (is (= [] (store/all-hypotheses s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/all-experiments s)))))

(deftest repository-backed-growth-store-restores-after-restart
  (let [dir (.toFile (Files/createTempDirectory
                      "growth-repository-" (make-array FileAttribute 0)))
        file (io/file dir "state.edn")
        environment {"KOTOBA_REPOSITORY_STATE_FILE" (.getPath file)}
        open-store #(store/datomic-store
                     {}
                     (edn-persist/configured-persist
                      environment "actor/shinshi-growth"))
        first-process (open-store)]
    (store/commit-record! first-process
                          {:effect :content-experiment :path ["restart-1"]
                           :payload {:summary "restored"}})
    (store/append-ledger! first-process {:t :committed})
    (let [second-process (open-store)]
      (is (= "restored"
             (:summary (store/experiment second-process "restart-1"))))
      (is (= [:committed] (mapv :t (store/ledger second-process)))))))

;; ───────────────────────── governor / operation contract ─────────────────────────

(defn- fixed-advisor
  "An advisor that always returns `proposal` regardless of the request — used
  to inject specific (including malicious/OOD) proposals directly, mirroring
  talent.hrllm's `:bias?`/`:greedy?` injection idiom but at the advisor level."
  [proposal]
  (reify growthllm/Advisor (-advise [_ _ _] proposal)))

(defn- exec [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(def ^:private phase3-ctx {:actor-id "growth-llm" :phase 3})

(deftest dark-pattern-copy-is-held-no-actuation
  (testing "an advisor proposing fake-urgency/scarcity copy → HOLD, nothing written"
    (let [db (store/seed-db)
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "今だけ限定！残りわずかのキャンペーン"
                                         :rationale "緊急性を煽ればCVRが上がる"
                                         :cites [] :effect :marketing-copy :confidence 0.95})})
          res (exec actor "t1" {:op :marketing-copy} phase3-ctx)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= [] (store/all-experiments db)) "no-actuation: nothing committed")
      (is (= 1 (count (store/ledger db))) "exactly one ledger fact")
      (is (some #{:charter-clean} (-> (store/ledger db) first :basis))))))

(deftest age-verification-copy-change-is-always-held
  (testing "an advisor proposing an age-verification/consent copy change → HOLD, un-overridable"
    (let [db (store/seed-db)
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "同意画面の文言を変更"
                                         :rationale "離脱率改善のため"
                                         :cites [:age-verification] :effect :content-experiment
                                         :confidence 0.9})})
          res (exec actor "t2" {:op :content-experiment} phase3-ctx)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= [] (store/all-experiments db)))
      (is (some #{:age-verification-untouchable} (-> (store/ledger db) first :basis))))))

(deftest unknown-effect-is-hard-violation
  (testing "an effect outside the enum is a hard violation → HOLD (fail closed on schema drift)"
    (let [db (store/seed-db)
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "x" :rationale "x" :cites []
                                         :effect :not-a-real-effect :confidence 0.99})})
          res (exec actor "t3" {:op :not-a-real-effect} phase3-ctx)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:unknown-effect} (-> (store/ledger db) first :basis))))))

(deftest high-stakes-always-escalates-even-when-clean-and-confident
  (testing "a clean, high-confidence pricing-experiment still requires human approval"
    (let [db (store/seed-db)
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "clean proposal" :rationale "実測に基づく"
                                         :cites [:H2 :ad-revenue-jpy] :effect :pricing-experiment
                                         :confidence 0.99})})
          res (exec actor "t4" {:op :pricing-experiment} phase3-ctx)]
      (is (= :interrupted (:status res)) "pauses for human approval regardless of confidence")
      (testing "approve → commit"
        (let [res2 (g/run* actor {:approval {:status :approved :by "itonami-operator"}}
                           {:thread-id "t4" :resume? true})]
          (is (= :commit (get-in res2 [:state :disposition])))
          (is (= :commit (-> (store/ledger db) last :disposition))))))))

(deftest ppv-terms-change-always-escalates
  (testing ":effect :ppv-terms-change always escalates, even clean + high confidence"
    (let [db (store/seed-db)
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "PPV terms adjustment" :rationale "実測に基づく"
                                         :cites [:H2] :effect :ppv-terms-change
                                         :confidence 0.99})})
          res (exec actor "t4b" {:op :ppv-terms-change} phase3-ctx)]
      (is (= :interrupted (:status res))))))

(deftest creator-payout-touch-always-escalates-even-for-low-stakes-effect
  (testing "a nominally low-stakes content-experiment that implies a creator-split change still escalates"
    (let [db (store/seed-db)
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "creator-split を見直す実験"
                                         :rationale "..." :cites [:creator-split]
                                         :effect :content-experiment :confidence 0.95})})
          res (exec actor "t5" {:op :content-experiment} phase3-ctx)]
      (is (= :interrupted (:status res))
          "creator-payout protection overrides the low-stakes auto-commit path"))))

(deftest low-stakes-clean-high-confidence-commits-only-at-phase-3
  (testing "clean content-experiment >= confidence floor auto-commits at phase 3"
    (let [db (store/seed-db)
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "clean" :rationale "clean"
                                         :cites [:H1] :effect :content-experiment
                                         :confidence 0.9})})
          res (exec actor "t6" {:op :content-experiment} phase3-ctx)]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest confidence-floor-below-threshold-escalates
  (testing "a low-stakes proposal below the 0.7 confidence floor escalates, not auto-commit"
    (let [db (store/seed-db)
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "unsure" :rationale "低確信"
                                         :cites [:H1] :effect :content-experiment
                                         :confidence 0.4})})
          res (exec actor "t7" {:op :content-experiment} phase3-ctx)]
      (is (= :interrupted (:status res))))))

(deftest this-repos-default-phase-0-holds-everything
  (testing "growth.phase/default-phase is 0 — even a clean, high-confidence proposal holds without an explicit phase in context"
    (let [db (store/seed-db)
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "clean" :rationale "clean"
                                         :cites [:H1] :effect :content-experiment
                                         :confidence 0.95})})
          res (exec actor "t8" {:op :content-experiment} {:actor-id "growth-llm"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= [] (store/all-experiments db)))
      (is (= :phase-disabled (-> (store/ledger db) first :phase-reason))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations → N ledger facts"
    (let [db (store/seed-db)
          actor (op/build db {:advisor (fixed-advisor
                                        {:summary "clean" :rationale "clean"
                                         :cites [:H1] :effect :content-experiment
                                         :confidence 0.95})})]
      (exec actor "a" {:op :content-experiment} phase3-ctx)
      (exec actor "b" {:op :content-experiment} {:actor-id "growth-llm"}) ; phase 0 default → hold
      (is (= 2 (count (store/ledger db)))))))
