(ns haystack.search
  (:require [clojure.set :as set]
            [clojure.string :refer [split join]]
            [clojure.set :refer [rename-keys]]

            [clj-http.client :as client]
            [bidi.bidi :as bidi]
            ;; [clojurewerkz.elastisch.rest.index :as esi]
            ;; [clojurewerkz.elastisch.rest :as esr] ;; for connect
            [clojurewerkz.elastisch.rest.document :as esd]

            [haystack.repo :refer [repo]]
            [haystack.query :as query]

            [haystack.ecommerce :refer [find-category-by-path find-manufacturer]]
            )
  (:import [java.net ConnectException SocketException]))

(defn merge-aggregation-names
  [aggregations]
  (let [cats (:category-path aggregations)
        manuf (:manufacturer-id aggregations)
        ancestors (:category-path-ancestors aggregations)
        ancestor-paths
        (map (fn [facet]
               (let [m (find-category-by-path (:key facet))]
                 (cond-> facet
                   m (assoc :name (:name m)))))
             ancestors)
        ancestor-paths
        (if (empty? ancestor-paths)
          ancestor-paths
          (let [last-key (:key (last ancestor-paths))
                dropped-counts (map #(if (= last-key (:key %))
                                       %
                                       (dissoc % :doc_count))
                                    ancestor-paths)
                ]
            (concat [{:key "" :name "All Categories"}] dropped-counts)))
        ]
    {:category-path (map (fn [facet]
                           (let [m (find-category-by-path (:key facet))]
                             (cond-> facet
                               m (assoc :name (:name m)))))
                         cats)
     :manufacturer-id (map (fn [facet] (let [m (find-manufacturer (:key facet))]
                                   (cond-> facet
                                     m (assoc :name (:name m)))))
                           manuf)
     :category-path-ancestors ancestor-paths
     }
    ))

;; (def q {:query {:query_string "copper"}})
;; (esd/search repo "searchecommerce" "productplus" q)
;; (:uri repo)

(defn process-search
  [query-map]
  (let [q (query/build-search-query query-map)
        response (try (esd/search repo "searchecommerce" "productplus" q)
                      (catch Throwable e
                        (println e) (println "err type:" (type e)) (prn (type e))
                        (condp = (type e)
                          java.net.ConnectException (println "connection error")
                          java.net.SocketException (println "socket error. Elasticsearch may have just come back and next search request may work.")
                          (println "error type:" (type e))
                          )
                        {}))
        aggregations (query/extract-aggregations query-map response)
        named-aggregations (merge-aggregation-names aggregations)
        ]
    (if (:total-items-only query-map)
      {:total-items (-> response :hits :total)
       :elasticsearch-query q
       }
      {:paging (query/extract-paging query-map response)
       ;; :transform (query/transform-search-query query-map)
       ;; :q q
       :search-request query-map
       :documents (query/extract-documents response)
       :aggregations named-aggregations
       :elasticsearch-query q
       :response response
       })))

(defn search
  [query-map]
  (let [query-map (let [m (:manufacturer-ids query-map)]
                    (cond-> query-map
                      m (assoc :manufacturer-ids (read-string m))
                      (= "" (:category-path query-map)) (dissoc :category-path)
                      ))
        _ (prn query-map)
        sc-id (:service-center-id query-map)
        entire? (or (= "true" (:query-entire query-map)) (not sc-id))
        query-map (dissoc query-map :query-entire)
        main-query-map (if entire? (dissoc query-map :service-center-id) query-map)
        main-response (future (process-search main-query-map))
        secondary-query-map (if entire?
                              (if sc-id query-map)
                              (dissoc query-map :service-center-id))
        secondary-query-map (when secondary-query-map
                              (assoc secondary-query-map :total-items-only true))
        secondary-response (when secondary-query-map
                             (future (process-search secondary-query-map)))
        hits (if entire?
               {:entire-item-count (-> @main-response :paging :total-items)
                :local-item-count (when secondary-response (-> @secondary-response :total-items))}
               {:entire-item-count (when secondary-response (-> @secondary-response :total-items))
                :local-item-count (-> @main-response :paging :total-items)}
               )
        ]
    (dissoc
     (assoc
      (assoc-in @main-response [:paging]
                (merge (:paging @main-response) hits))
      :query-maps {:main main-query-map
                   :secondary secondary-query-map
                   :main-elasticsearch (:elasticsearch-query @main-response)
                   :secondary-elasticsearch (when secondary-response
                                              (:elasticsearch-query @secondary-response))
                   })
     :elasticsearch-query)
    ))

;; (search {:search "copper"})


