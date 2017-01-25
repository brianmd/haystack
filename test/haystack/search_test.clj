(ns haystack.search-test
  (:require [haystack.search :as sut :refer [search]]
            ;; [clojure.test :as t :refer :all]

            [haystack.helpers :refer :all]
            ))

;; The following are common searches and definitions
;; of what should be returned.

;; As an organization, we've tended to modify the search
;; algorithm to improve a specific use case, which often
;; inadvertenly damaged other common use cases.

;; Defining these search use cases is an attempt
;; at preventing these perturbations of the force.

(test-search matnrs-check
 {:search "2843977 2542450"}
 (max-docs 50)
 (in-top 2 2843977 2542450)
 )

(test-search upcs-check
 {:search "078477045442 980100350109"}
 (max-docs 500)
 (in-top 2 199152 2542450)
 )

(test-search copper-clip-check
 ;; set num-per-page to return up to 100 documents
 {:search "copper clip" :num-per-page 100}
 (max-docs 100)
 ;; 20127: "copper" and "clip" not in the same field
 ;; 3336524: "clip" is in the part number
 (in-top 100 20127 3336524)
 )

