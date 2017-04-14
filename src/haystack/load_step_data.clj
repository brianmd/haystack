(ns haystack.load-step-data
  (:require [clojure.core.async :as a]
            [clojure.data.csv :as csv]
            [clojure.java.jdbc :as j]
            [clojure.string :as string]
            [haystack.ecommerce :as ecommerce]))

(defn as-matnr
  [s]
  (let [s (str s)
        zeroed (str "000000000000000000" s)]
    (subs zeroed (count s))))

(defn read-step-file-aux
  [channel field-names filename]
  (with-open [in (clojure.java.io/reader filename)]
    (doseq [data (csv/read-csv in :separator \tab)]
      (a/>!! channel (zipmap field-names data)))
    (println "closing ...")
    (a/close! channel)
    ))

(defn read-step-file
  [filename c]
  (future
    (do
      (read-step-file-aux
       c
       [:id :step_name :step_title :step_description :step_unspsc]
       filename
       )
      (println "\n\ndone with process-step-file\n\n"))))

(defn write-to-db
  [m]
  (if (string/blank? (:id m))
    (prn ["no id for: " m])
    (let [id (:id m)
          flds (dissoc m :id :step_name :step_unspsc)]
      (j/update! @ecommerce/db-map :products flds ["id = ?" id])
      )))

(defn load-step-file
  [c]
  (loop []
    (when-some [v (a/<!! c)]
      ;; (prn ["v" v])
      (write-to-db v)
      (recur))))

(defn process-step-file
  [filename]
  (let [c (a/chan 10)]
    (future
      (read-step-file filename c))
    (load-step-file c)
    (println "\n\ndone process-file " filename)
    ))

;; (haystack.load-step-data/process-step-file "/home/bmd/downloads/csv-1492021920379.tsv")
;; (process-step-file "/home/bmd/downloads/csv-1492022325426.tsv")
;; (process-step-file "/home/bmd/downloads/csv-1492022928025.tsv")
;; (process-step-file "/home/bmd/downloads/csv-1492023528629.tsv")
;; (process-step-file "/home/bmd/downloads/csv-1492024123442.tsv")



