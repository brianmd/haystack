(ns haystack.query-test
  (:require [haystack.query :as sut]
            [clojure.test :as t :refer [deftest is]]
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

(deftest build-query
  (let [elasticsearch-query (sut/build-search-query q)]
    (is (= (:query elasticsearch-query)
           {:bool {:filter [{:terms {:manufacturer-id [370]}} {:term {:service-center-ids 7}} {:term {:category-ids 54}}], :should [{:query {:term {:upc "12345678901 032886912344"}}}], :must [{:match {:_all {:query "heavy ground 12345678901 12345678901 bar type safety 032886912344", :operator "and"}}}]}}
           ))))

;; (deftest select-upcs
;;   (is (=
;;        (sut/select-upcs ["abc" "1234567890" "12345678901" "123456789012" "1234567890333" "12345678904444" "123456789055555"])
;;        ["12345678901" "123456789012" "234567890333" "345678904444"])))
