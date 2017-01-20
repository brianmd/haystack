(ns haystack.helpers
  (:require  [clojure.test :as t :refer :all]
             [haystack.search :as sut :refer [search]]
             ))

(defn hit-count
  [response]
  (-> response :paging :total-items))

(defn extract-matnr
  [s]
  (->> s (re-matches #"[^\d]*([\d]+).*") last))

(defn matnrs
  [response]
  (map #(extract-matnr (:matnr %)) (-> response :documents)))

(defmacro max-docs
  [n]
  `(is (<= (hit-count ~'*response*) ~n)))

(defmacro in-top
  [matnr n]
  `(is (contains? (set (take ~n ~'*matnrs*)) (str ~matnr))))

(defmacro test-search
  [name query-map & tests]
  `(deftest ~name
     (let [~'*response* (search ~query-map)
           ~'*matnrs* (matnrs ~'*response*)]
       ~@tests))
  )

;; (macroexpand-1 '(test-search abc {:search "beef"}
;;                            (max-docs 50)))
;; (macroexpand '(test-search abc {:search "beef"}
;;                            (max-docs 50)))
;; (macroexpand '(deftest abc (let [result (search {:search "beef"})])))

