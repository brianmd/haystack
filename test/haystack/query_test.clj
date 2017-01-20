(ns haystack.query-test
  (:require [haystack.query :as sut]
            [clojure.test :refer :all]
            [haystack.repo :as r]
            ))

(def q
  {
   :search "heavy ground 12345678901 12345678901 bar type safety 032886912344"
   ;; :search-within "ground bar type safety"
   :manufacturer-ids [370]
   :category-path "/388/54"
   :category-id 54
   :service-center-id 7
   })

(deftest to-from-url
  (is (= q (-> q sut/->url sut/<-url)))
  )

;; (deftest live-queries
;;   (let [result (sut/query {:search "783310892502"})]
;;     )
;;   (let [result (sut/query {:search "78331089250"})]
;;     )
;;   )



;; add document types aggregation to top of search results page

"
# queries to test (product only):
_upc_
_matnr_
_order #_
_invoice #_
manufacturer part #
summit part #
general search words
(in conjunction with one or more of the following)
w/ service center
w/ manufacturer-ids
w/ category path
** extra credit: w/ attributes, e.g., color, wire size



     --------------------
# queries to test (other types [test w/ and w/o including products]):
general search words



     --------------------
# things to test on the above queries:
1. what should show in suggestions (when partially typed and when completed)
2. what should show in search results

top 10 documents returned
document-type aggregations
category aggregations
manufacturer aggregations
** attribute aggregations

"
