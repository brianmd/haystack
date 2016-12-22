(ns haystack.query
  (:require [clojure.set :as set]
            [clojure.string :refer [split join]]
            [clojure.set :refer [rename-keys]]

            [clj-http.client :as client]
            [bidi.bidi :as bidi]
            ))

(def ^:private search-keys #{:search :service-center-id :manufacturer-ids :category-path})

;; ----------------------------  utility functions

(defn- stringify-seq
  "turn sequence into a string separated by spaces (by default), after removing duplicates"
  ([s]
   (stringify-seq s " "))
  ([s sep]
   (join sep (map str (set s)))))

(defn parse-long [s]
  (if (string? s)
    (if-let [token (re-find #"\d+" s)]
      (if (string? token)
        (Long. token)))))

(defn ->long [s]
  (if-let [l (parse-long s)]
    l
    0))

(defn parse-float [s]
  (if (string? s)
    (if-let [token (re-find #"\d+\.?\d+" s)]
      (if (string? token)
        (Double. token)))))
;; (assert (= (parse-float "$2.32 ea 44.2") 2.32))

(defn slash-count
  [path]
  (if (string? path)
    (get (frequencies path) \/)
    0))

(defn slash-count= [cnt path]
  (= cnt (slash-count path))
  )

(defn categories-at-level
  [cats level]
  (filter #(slash-count= level (:key %)) cats)
  )


;; ------------------------------------------------ extract info

(defn build-document
  [doc]
  (let [source (atom (select-keys
                      (:_source doc)
                      [:bh-product-id :name :description :manufacturer-name :manufacturer-part-number
                       :summit-part-number :upc :category-name :product-class :matnr :image-url]))
        highlight (:highlight doc)]
    (doall
     (map (fn [[k v]] (swap! source assoc k (first v))) highlight))
    (rename-keys @source {:bh-product-id :id})))

(defn extract-documents
  [response]
  (let [docs (-> response :hits :hits)]
    (map build-document docs)))

(defn extract-aggregations
  [query-map response]
  (let [aggs (:aggregations response)
        cats (-> aggs :category-path :buckets)
        manuf (-> aggs :manufacturer-id :buckets)
        cat-path (:category-path query-map)
        cat-path-level (inc (slash-count cat-path))
        cat-ancestors (flatten (map #(categories-at-level cats %) (range 1 cat-path-level)))
        ]
    {:category-path (categories-at-level cats cat-path-level)
     :category-path-ancestors cat-ancestors
     :manufacturer-id manuf}))

(defn extract-paging
  [query-map response]
  (let [num-per-page (-> (->long (-> query-map :num-per-page)) (max 10) (min 100))
        page-num (-> (->long (-> query-map :page-num)) (max 1))
        total-items (-> response :hits :total)]
    {:page-num page-num
     :num-per-page num-per-page
     :total-items total-items
     :num-pages (when total-items (-> (/ total-items num-per-page) Math/ceil int))
     }))


;; ------------------------------------------------

(defn- validate-query-map
  [query-map]
  true)
  ;; (set/subset? (keys query-map) search-keys))

(defn- select-upcs [tokens]
  (let [upcs (remove nil? (map #(re-matches #"\d{11,14}" %) tokens))]
    (map #(let [n (- (count %) 12)] (subs % (max 0 n))) upcs)))

(def fields-to-search
  ;; ["name" "description" "manufacturer-name" "category-name" "product-class" "upc" "manufacturer-part-number" "summit-part-number" "matnr"])
  ["name" "description" "category-name" "product-class" "upc" "manufacturer-part-number" "summit-part-number" "matnr"])

(defn- transform-search-query
  "discover must/should/filtered components of query-map"
  [{:keys [search service-center-id manufacturer-ids category-path] :as query-map}]
  {:pre [(validate-query-map query-map)]}
  (let [tokens      (when search (split search #"\s+"))
        upcs        (select-upcs tokens)
        category-id (when category-path (-> (split category-path #"/") last))]
    (cond-> {:filtered [] :must [] :should []}
      manufacturer-ids  (update-in [:filtered] conj {:terms {:manufacturer-id manufacturer-ids}})
      service-center-id (update-in [:filtered] conj {:term {:service-center-ids service-center-id}})
      category-id       (update-in [:filtered] conj {:term {:category-ids category-id}})

      (not-empty search) (update-in [:must] conj
                                  {:multi_match {:query    search
                                                 :type     "cross_fields"
                                                 :operator "and"
                                                 ;; :minimum_should_match "75%"
                                                 :fields   fields-to-search}})
      (not-empty upcs) (update-in [:should] conj {:query {:term {:upc (stringify-seq upcs)}}})
      ;; service-center-count -- boost? filter?
      )))
;; (transform-search-query query-map)

(defn build-search-query
  "build elasticsearch json query from query-map"
  [query-map]
  (let [q (transform-search-query query-map)
        paging (extract-paging query-map nil)
        do-aggs (not (:total-hits-only query-map))]
    (cond->
        {:from (* (:num-per-page paging) (dec (:page-num paging)))
         :size (:num-per-page paging)
         :query
         {:bool
          (cond-> {}
            (not-empty (:filtered q)) (assoc :filter (:filtered q))
            (not-empty (:should q)) (assoc :should (:should q))
            (not-empty (:must q)) (assoc :must   (:must q)))
          }
         :highlight
         {:pre_tags ["<span class=\"search-term\">"]
          :post_tags ["</span>"]
          :fields
          {:* {}}}
         }
      do-aggs (assoc :aggregations
                  {:category-path   {:terms {:field "category-path" :min_doc_count 1 :size 4000}}
                   :manufacturer-id {:terms {:field "manufacturer-id" :min_doc_count 1 :size 100}}
                   })
      )))
;; (build-search-query query-map)



;; TODO: currently blue-harvest accepts only one manufacturer
;; TODO: currently blue-harvest returns "selected" rather than the service center id
(defn ->url
  "create url string from query-map"
  [{:keys [search service-center-id manufacturer-ids category-id category-path] :as query-map}]
  (let [pieces
        (cond-> {}
          search            (assoc :search search)
          manufacturer-ids  (assoc :manufacturer (first manufacturer-ids))
          category-path     (assoc :category category-path)
          service-center-id (assoc :sc_filter service-center-id)
          )]
    pieces
    (str ; "https://www.summit.com/products?"
         (client/generate-query-string pieces))
    ))

(defn <-url
  "parse url into a query-map"
  [url]
  (let [tokens (split url #"&")
        params (map #(split % #"=") tokens)
        m      (into {} (map #(vector (-> % first keyword) (-> % second bidi/url-decode)) params))
        {:keys [search sc_filter manufacturer category]} m]
    (cond-> {}
      search       (assoc :search search)
      sc_filter    (assoc :service-center-id (read-string sc_filter))
      manufacturer (assoc :manufacturer-ids [(read-string manufacturer)])
      category     (assoc :category-path category)
      category     (assoc :category-id (-> category (split #"/") last read-string))
      )
    ))



;;      for testing

;; (def query-map
;;   {
;;    ;; :search "COILPAK"
;;    ;; :search "copper 40574"
;;    :search "copper"
;;    ;; :search "matnr:2580491"
;;    ;; :search "matnr:000000000002580491 476M40M25NP"
;;    ;; :search "000000000002580491"
;;    ;; :search "HA476M40M25NP"
;;    ;; :search "476M40M25NP M25F Hubbell"
;;    ;; :search "led chain coilpak"
;;    ;; :search-within "ground bar type safety"
;;    ;; :manufacturer-ids [370 43 "113"]
;;    ;; :category-path
;;    ;; "/388/54"
;;    ;; "/390/83/173/348"
;;    ;; :category-id 173
;;    ;; :category-id 83
;;    ;; :category-id 172
;;    ;; :service-center-id 38
;;    })
;; (defn quick-query []
;;   (clojurewerkz.elastisch.rest.document/search
;;    haystack.elastic/repo
;;    "searchecommerce"
;;    "productplus"
;;    (build-search-query query-map)))
;; ;; (quick-query)
