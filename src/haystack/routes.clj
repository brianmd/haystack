(ns haystack.routes
  (:require [clojure.walk :as walk]
            [yada.yada :as yada]
            [bidi.bidi :as bidi]
            [bidi.vhosts :refer [vhosts-model]]

            [clojurewerkz.elastisch.rest :as esr] ;; for connect
            [clojurewerkz.elastisch.rest.document :as esd]

            ;; [haystack.elastic :refer [reload]]
            [haystack.repo :refer [repo]]
            [haystack.query :as query]
            [haystack.create-index :as create-index]

            [haystack.ecommerce :as ecommerce]
            [clojurewerkz.elastisch.rest.index :as esi]))

(defn simple-text-page
  [text]
  (str "<html><body>" text "</body></html"))

(defn json-response
  [json]
  {:status 200
   :body json})

(def my-routes ["/" {"index.html" :index
                     "articles/" {"index.html" :article-index
                                  "article.html" :article}}])

(defn save-feedback
  [ctx]
  (let [query-map (-> ctx :parameters :query walk/keywordize-keys)
        query-map (merge
                   query-map
                   {:created-on (java.util.Date.)})]
    (println "in save-feedback")
    ;; (println (-> ctx :parameters :query walk/keywordize-keys))
    (prn query-map)
    (try
      (esd/create repo "searchecommerce" "feedback" query-map)
      (catch Throwable e
        (println e)
        (throw e)))
    (json-response {:saved true})))

(defn get-feedback
  []
  (esd/search repo "searchecommerce" "feedback" ""))

(def scheme "http")
(def host "locahost:8080")

(defn merge-aggregation-names
  [aggregations]
  (let [cats (:category-path aggregations)
        manuf (:manufacturer-id aggregations)
        ancestors (:category-path-ancestors aggregations)
        ancestor-paths
        (map (fn [facet]
               (let [m (ecommerce/find-category-by-path (:key facet))]
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
                           (let [m (ecommerce/find-category-by-path (:key facet))]
                             (cond-> facet
                               m (assoc :name (:name m)))))
                         cats)
     :manufacturer-id (map (fn [facet] (let [m (ecommerce/find-manufacturer (:key facet))]
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
        response (try (esd/search repo "searchecommerce" "productplus" q) (catch Throwable e {}))
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
  [ctx]
  (let [query-map (-> ctx :parameters :query walk/keywordize-keys)
        query-map (let [m (:manufacturer-ids query-map)]
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


;; (restart-server)
(defn example-page [req]
  (let [;; host (or (-> req :parameters :host) (System/getenv "HAYSTACK_HOST") "localhost:8080")
        ;; scheme "http"
        ;; base-url (str scheme "://" host)
        base-url ""
        ]
    {:status 200
     :body   (str "<html><body>Admin commands:<br/><a target='_blank' href='sql'>sql</a>, <a target='_blank' href='mappings'>mappings</a>, <a target='_blank' href='my-mappings'>my-mappings</a>, <a target='_blank' href='reload'>reload</a><br/><br/>Example searches:<br/><a target='_blank' href='" base-url "/api/v2/search?search=copper%20blue&category-path=/395&page-num=1&service-center-id=7&manufacturer-ids=[127,199]&query-entire=false" "'>multi-manufacturers</a> <a target='_blank' href='" base-url "/api/v2/search?search=783250301652&service-center-id=7&query-entire=false'>upc</a> <a target='_blank' href='" base-url "/api/v2/search?search=21234&page-num=1&query-entire=true'>matnr</a> <a target='_blank' href='" base-url "/api/v2/search?search=KL70581&page-num=1&query-entire=true'>part-num</a> <a target='_blank' href='" base-url "/api/v2/search?search=copper%201388727&category-path=/395&page-num=1&query-entire=false" "'>bad</a> <a target='_blank' href='" base-url "/api/v2/search?search=wacky&page-num=1&query-entire=false" "'>stibo</a></body></html>")}
    ))
;; (example-page {:parameters {:host "*****"}})

(def routes
  ["/" {"dump" (fn [req]
                 {:status 200
                  :body   "<html><body>hi</body></html>"})

        "admin/"
        {"commands"   (fn [req] (example-page req))
         "sql" (yada/resource
                {:id :sql
                 :description "Sql for extracting ecommerce data from db"
                 :produces    {:media-type "application/json"}
                 :methods
                 {:get
                  {:response (fn [ctx] {:sql ecommerce/product-query-sql})}}})
         "orig-mappings"
                      (yada/resource
                       {:id :sql
                        :description "Current mappings (both explicitly specified and gleaned from the data)"
                        :access-control {:allow-origin "*"
                                         :allow-methods #{:get :post}}
                        :produces    {:media-type "application/json"}
                        :methods
                        {:get
                         {:response (fn [ctx] (esi/get-mapping repo "searchecommerce"))}}})
         "mappings" (yada/resource
                       {:id :sql
                        :description "Current mappings (both explicitly specified and gleaned from the data)"
                        :access-control {:allow-origin "*"
                                         :allow-methods #{:get :post}
                                         :allow-credentials false
                                         :expose-headers #{"X-Custom"}}
                        :produces    {:media-type "application/json"}
                        :methods
                        {:get
                         {:response (fn [ctx]
                                      (println "in mappings")
                                      (esi/get-mapping repo "searchecommerce"))}}})
        "my-mappings" (yada/resource
                 {:id :sql
                  :description "Explicitly defined mappings"
                  :produces    {:media-type "application/json"}
                  :methods
                  {:get
                   {:response (fn [ctx] create-index/ecommerce-mapping-types)}}})
         "reload" (yada/resource
                     {:id :reload
                      :description "Reload ecommerce data into elasticsearch"
                      :produces    {:media-type "application/json"}
                      :methods
                      {:get
                       {:response (fn [ctx]
                                    (try
                                      (do
                                        (println "in reload section")
                                        (create-index/reload repo "searchecommerce")
                                        {:reloaded true})
                                      (catch Throwable e
                                        {:reloaded false})))}}})
         }

        []   (yada/resource
              {:id          :homepage
               :description "The homepage"
               :produces    {:media-type "text/html"
                             :language   "en"}
               :methods
               {:get
                {:response (fn [ctx] (simple-text-page "homepage"))}}})

        "api/v2/"
        {"dump"   (fn [req]
                    {:status 200
                     :body   "<html><body>api/v2/</body></html>"})
         "feedback" (yada/resource
                     {:id :feedback
                      :description "Post feedback"
                      :access-control {:allow-origin "*"
                                       :allow-methods #{:get :post}
                                       :allow-credentials false
                                       :expose-headers #{"X-Custom"}}
                      :consumes    {:media-type #{"application/json" "application/transit" "application/transmit+json"}}
                      :produces    {:media-type "application/json"}
                      :methods
                      {:get
                       {:response (fn [ctx] (save-feedback ctx))}}})
         "search" (yada/resource
                   {:id          :homepage
                    :description "Process search request"
                    :access-control {:allow-origin "*"
                                     :allow-methods #{:get :post}
                                     :allow-credentials false
                                     :expose-headers #{"X-Custom"}}
                    :consumes    {:media-type #{"application/json" "application/transit" "application/transmit+json"}}
                    ;; :consumes    {:media-type "application/json"}
                    ;; :produces    {:media-type #{"application/json" "application/transit" "application/transmit+json"}}
                    :produces    {:media-type "application/json"}
                    ;; :produces    {:media-type "application/transit"}
                    :methods
                    {:get
                     {:response (fn [ctx]
                                  ;; (println (keys ctx))
                                  ;; (println (-> ctx :parameters))
                                  (search ctx))}}})
         ;; {:response (fn [ctx] {:path (-> ctx :parameters :path)
         ;;                       :query (-> ctx :parameters :query)})}}})
         [:id]    (yada/resource
                   {:id          :homepage
                    :description "Json test"
                    :produces    {:media-type "application/json"}
                    :methods
                    {:get
                     ;; {:response (fn [ctx] {:a (-> ctx :parameters :path )})}}})
                     {:response (fn [ctx] {:path  (-> ctx :parameters :path)
                                           :query (-> ctx :parameters :query)})}}})
         "json"   (yada/resource
                   {:id          :homepage
                    :description "Json test"
                    :produces    {:media-type "application/json"}
                    :methods
                    {:get
                     {:response (fn [ctx] {:a (-> ctx :parameters keys str)})}}})
         }
        }
     ])

(defonce server (atom nil))

(defn start-server
  []
  (let [listener (yada/listener
                  routes
                  {:port 8080
                   :host "0.0.0.0"}
                  )]
    (reset! server listener)
    listener))

(defn close-server
  []
  (when @server
    ((:close @server))
    (reset! server nil)))

(defn restart-server
  []
  (close-server)
  (start-server))

;; (create-index/reload repo "searchecommerce")

