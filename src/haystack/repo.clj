(ns haystack.repo
  (:require [clojurewerkz.elastisch.rest :as esr] ;; for connect
            [haystack.query :as query]
            ))

;; (def srv "http://192.168.0.220:9201")
;; (def srv "http://192.168.0.220:9200")
;; (def srv "http://gandalf-the-white:9200")
(def srv (or (System/getenv "ELASTICSEARCH_URL") "http://127.0.0.1:9200"))

(def index-name "searchecommerce")

(def repo (esr/connect srv
                       {; :cluster.name "ek"
                        :connection-manager (clj-http.conn-mgr/make-reusable-conn-manager
                                             {:timeout 10})}))

