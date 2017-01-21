(ns haystack.helpers
  (:require  [clojure.test :as t :refer :all]
             [haystack.search :as sut :refer [search]]
             ))

(defn hit-count
  "return number of hits from search response"
  [response]
  (-> response :paging :total-items))

(defn extract-digits
  "extract digits (upc/matnr) from string.
  Useful when possibly inside a span html tag."
  [s]
  (->> s (re-matches #"[^\d]*([\d]+).*") last))

(defn matnrs
  "return collection of matnrs from search response (lazy)"
  [response]
  (map #(extract-digits (:matnr %)) (-> response :documents)))

(defmacro min-docs
  "raise test error if less than n possible documents in search response.
  This is the number of hits, not the number documents returned in this page."
  [n]
  `(is (<= ~n (hit-count ~'*response*))))

(defmacro max-docs
  "raise test error if more than n possible documents in search response.
  This is the number of hits, not the number documents returned in this page."
  [n]
  `(is (<= (hit-count ~'*response*) ~n)))

(defmacro not-in-top
  "raise error if document with given matnr is within the top n results.
  Use this to ensure a specific document is not scored high."
  [matnr n]
  `(is (not (contains? (set (take ~n ~'*matnrs*)) (str ~matnr)))))

(defmacro in-top
  "raise error if document with given matnr isn't within the top n results.
  Note: if n>10, should include :num-per-page in search query-map."
  [matnr n]
  `(is (contains? (set (take ~n ~'*matnrs*)) (str ~matnr))))

(defmacro test-search
  "define search use case"
  [name query-map & tests]
  `(deftest ~name
     (let [~'*response* (search ~query-map)
           ~'*matnrs* (matnrs ~'*response*)]
       ~@tests))
  )
