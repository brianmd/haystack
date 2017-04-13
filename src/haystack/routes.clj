(ns haystack.routes
  (:require [clojure.walk :as walk]
            [yada.yada :as yada]
            [bidi.bidi :as bidi]
            [bidi.vhosts :refer [vhosts-model]]

            ;; [clojurewerkz.elastisch.rest :as esr] ;; for connect
            [clojurewerkz.elastisch.rest.document :as esd]
            [clojurewerkz.elastisch.rest.index :as esi]

            ;; [haystack.elastic :refer [reload]]
            [haystack.repo :refer [repo]]
            [haystack.query :as query]
            [haystack.search :as search]
            [haystack.feedback :as feedback]
            [haystack.create-index :as create-index]

            [haystack.ecommerce :as ecommerce]

            [mishmash.event-logger :as event-logger]
            [mishmash.http-handlers :as http-handlers]
            [mishmash.log :as l]
            ))

(swap! http-handlers/base-event assoc :program "haystack")

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

(defn example-page [req]
  (let [base-url ""]
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
                                       :allow-headers "Origin, X-Requested-With, Content-Type, Accept"
                                       :allow-methods #{:get :post}
                                       :allow-credentials false
                                       :expose-headers #{"X-Custom"}
                                       }
                      ;; :consumes    {:media-type #{"application/json" "application/transit" "application/transmit+json"}}
                      :consumes    {:media-type "application/json"}
                      :produces    {:media-type "application/json"}
                      :methods
                      {:get
                       {:response (fn [_]
                                    (json-response (feedback/get-feedback)))}
                       :post
                       {:response (fn [ctx] (println "save feedback")
                                    (let [params (-> ctx :parameters :query walk/keywordize-keys)]
                                      (event-logger/log-duration
                                       {:service "haystack-feedback-duration" :request "search" :params params :tags ["feedback" "haystack" "duration"]}
                                       #(let [result (feedback/save-feedback params)]
                                         (json-response {:saved result})))))
                        }}})

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
                                  (let [params (-> ctx :parameters :query walk/keywordize-keys)]
                                    (event-logger/log-duration
                                     {:service "haystack-search-duration" :request "search" :params params :tags ["haystack" "duration" "search"]}
                                     #(search/search params)
                                     ))
                                  ;; (search ctx)
                                  )}}})
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
  (println "listening on port " 8080)
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

