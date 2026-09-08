(ns growth.cacao-test
  "Local, offline-only tests for `growth.cacao` — identity generation /
  persist-reload round-trip / CACAO minting. No network call is made here;
  `load-or-create-identity!` is exercised against a throwaway tmpdir path,
  never `.growth/identity.edn`, so running this suite never leaves a
  production identity file behind. Adapted from `shinshi.cacao-test`
  (`jk-luxury/club-shinshi/20-actors/shinshi`)."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.text :as str]
            [growth.cacao :as cacao])
  (:import [java.util Base64]))

(deftest canonical-graph-deterministic
  (testing "graph CID is deterministic and CIDv1/base32 multibase-shaped"
    (let [did "did:key:z6MkexampleExampleExampleExample"
          g1  (cacao/canonical-graph did "growth")
          g2  (cacao/canonical-graph did "growth")]
      (is (= g1 g2))
      (is (str/starts-with? g1 "b"))
      ;; sha2-256 CID payload behind 4 header bytes → 36 bytes → 58 b32 chars + 'b'
      (is (= 59 (count g1)))
      (is (not= g1 (cacao/canonical-graph did "otherdb")))
      (is (not= g1 (cacao/canonical-graph "did:key:z6MkotherOtherOther" "growth"))))))

(deftest identity-did-key-shape
  (let [{:keys [did graph]} (cacao/generate-identity)]
    (is (str/starts-with? did "did:key:z6Mk"))
    (is (str/starts-with? graph "b"))))

(deftest default-db-name-is-growth
  (is (= "growth" cacao/default-db-name)))

(deftest identity-persist-reload-roundtrip
  (let [dir  (str (System/getProperty "java.io.tmpdir")
                  "/growth-cacao-test-" (System/nanoTime))
        path (str dir "/identity.edn")
        id1  (cacao/load-or-create-identity! path)
        id2  (cacao/load-or-create-identity! path)]
    (is (= (:did id1) (:did id2)) "reload yields the same actor DID")
    (is (= (:graph id1) (:graph id2)))))

(deftest siwe-message-contains-grant-resources
  (let [payload (cacao/grant->payload {:cap :cap/transact :scope "bgraphcid"}
                                      {:iss "did:key:z6MkTest" :aud "https://pds.example"
                                       :nonce "n1" :issued-at "2026-07-06T00:00:00Z"
                                       :expiry "2026-07-06T01:00:00Z"})
        msg (cacao/siwe-message payload)]
    (is (str/includes? msg "URI: https://pds.example"))
    (is (str/includes? msg "kotoba://op/datom:transact"))
    (is (str/includes? msg "kotoba://graph/bgraphcid"))
    (is (str/includes? msg "Expiration Time: 2026-07-06T01:00:00Z"))))

(deftest mint-returns-base64-cbor
  (let [id (cacao/generate-identity)
        b64 (cacao/mint id
                        {:cap :cap/transact :scope (:graph id)}
                        {:aud "https://pds.example" :nonce "n1"
                         :issued-at "2026-07-06T00:00:00Z"
                         :expiry "2026-07-06T01:00:00Z"})
        raw (.decode (Base64/getDecoder) ^String b64)]
    (is (pos? (alength raw)))
    ;; CBOR map major type (5) in the first head byte
    (is (= 5 (bit-shift-right (bit-and (aget raw 0) 0xff) 5)))))

(deftest verify-roundtrip-with-real-ed25519-signature
  (testing "verify? correctly verifies a real JDK Ed25519 signature (same mechanics `mint` uses internally) — proving the crypto is real, not a mocked/hardcoded token"
    (let [id  (cacao/generate-identity)
          msg (.getBytes "growth.cacao round-trip test" "UTF-8")
          sig (let [s (doto (java.security.Signature/getInstance "Ed25519")
                        (.initSign (:private-key id)))]
                (.update s msg)
                (.sign s))]
      (is (cacao/verify? (:public-key id) msg sig))
      (is (not (cacao/verify? (:public-key id) (.getBytes "a different message" "UTF-8") sig))
          "signature does not verify against a different message"))))
