(ns haystack.feedback
  (:require [clojure.set :as set]
            [clojure.string :refer [split join]]
            [clojure.set :refer [rename-keys]]

            [clojurewerkz.elastisch.rest.index :as esi]
            [clojurewerkz.elastisch.rest.document :as esd]

            [haystack.repo :as r]
            ;; [clj-http.client :as client]
            ;; [bidi.bidi :as bidi]
            ))

(defn create-index
  []
  (try
    (do (esi/create r/repo "feedback" {})
        "created")
    (catch Throwable e
      (if (re-find #"already.exists" (str e))
        "already-existed"
        (do
          (println "error in index create")
          (println (re-find #"already.exists" (str e)))
          (throw e))))))

(defn get-feedback
  []
  (esd/search r/repo "searchecommerce" "feedback" ""))

(defn save-feedback
  [query-map]
  (let [;;query-map (-> ctx :parameters :query walk/keywordize-keys)
        query-map (merge
                   query-map
                   {:created-on (java.util.Date.)})]
    ;; (println "in save-feedback")
    ;; (println (-> ctx :parameters :query walk/keywordize-keys))
    (prn query-map)
    (try
      (esd/create r/repo "feedback" "feedback" query-map)
      (catch Throwable e
        (println e)
        (throw e)))
    true
    ))

