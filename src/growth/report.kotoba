(ns growth.report
  "ReportActor — plain-text views over the growth-loop SSoT. Renders exactly
  what `growth.store` holds; it does not itself read live data (Phase 0 has
  none — see `growth.facts`)."
  (:require [kotoba.lang.text :as str]
            [growth.store :as store]))

(defn hypothesis-backlog-text
  "The BMC hypothesis backlog this actor's proposals must cite against."
  [db]
  (str/join "\n"
            (for [h (store/all-hypotheses db)]
              (str "- " (name (:id h)) " [" (name (:risk h)) "/" (name (:status h)) "] "
                   (:gate h)))))

(defn experiment-ledger-text
  "One line per committed experiment/effect record."
  [db]
  (str/join "\n"
            (for [e (store/all-experiments db)]
              (str "- " (:id e) " effect=" (some-> (:effect e) name)
                   " summary=" (:summary e)))))

(defn audit-ledger-text
  "The full append-only decision ledger, human-readable."
  [db]
  (str/join "\n" (map store/ledger-line (store/ledger db))))
