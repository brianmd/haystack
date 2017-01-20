(ns haystack.search-test
  (:require [haystack.search :as sut :refer [search]]
            ;; [clojure.test :as t :refer :all]

            [haystack.helpers :refer :all]
            ))

(test-search
 matnrs-check
 {:search "2843977 2542450"}
 (max-docs 50)
 (in-top 2843977 2)
 (in-top 2542450 2)
 )

(test-search
 upcs-check
 {:search "078477045442 980100350109"}
 (max-docs 500)
 (in-top 199152 2)
 (in-top 2542450 2)
 )

