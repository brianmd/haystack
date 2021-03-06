(ns haystack.query
  (:require [clojure.set :as set]
            [clojure.string :refer [split join]]
            [clojure.set :refer [rename-keys]]

            [clj-http.client :as client]
            [bidi.bidi :as bidi]

            [clojure.string :as string]))

(defn fprintln
  "println and then flush"
  [& args]
  (.write *out* (str (clojure.string/join " " args) "\n")))

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
        (Long. token)))
    s))

(defn ->long [s]
  (if-let [l (parse-long s)]
    l
    0))

(defn parse-float [s]
  (if (string? s)
    (if-let [token (re-find #"\d+\.?\d+" s)]
      (if (string? token)
        (Double. token)))
    s))
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
  (if (= level 0)
    (let [docs (filter #(slash-count= 1 (:key %)) cats)]
      ;; (fprintln (str "level 0 cats:" (first docs)))
      ;; (prn cats)
      ;; [(assoc (first docs) :name "All Categories" :key "")])
      [:key 0 :doc-count (:doc-count (first docs))])
    (filter #(slash-count= level (:key %)) cats))
  )


;; ------------------------------------------------ extract info

(defn- build-highlight
  [v]
  (let [highlight (join "" v)]
    (if (re-find #"search-term" highlight)
      highlight
      (str "<span class=\"search-term\">" highlight "</span>"))))

(defn build-document
  [doc]
  (let [source (atom (select-keys
                      (:_source doc)
                      [:bh-product-id :name :description :manufacturer-name :manufacturer-part-number
                       :manufacturer-id
                       :summit-part-number :upc :category-name :product-class :matnr :image-url]))
        highlight (:highlight doc)]
    (doall
     ;; (map (fn [[k v]] (swap! source assoc k (join " .... " v))) highlight))
     (map (fn [[k v]] (swap! source assoc k (build-highlight v))) highlight))
    (rename-keys @source {:bh-product-id :id})))

(defn extract-documents
  [response]
  (let [docs (-> response :hits :hits)]
    (map build-document docs)))

(defn build-zero-ancestor
  [path]
  (if-let [ids (drop 1 (string/split (or path "") #"/"))]
    (let [path-history (atom [])]
      (map (fn [x]
             (swap! path-history conj x)
             {:key (str "/" (string/join "/" @path-history)) :doc_count 0})
           ids))))
;; (build-zero-ancestor "")
;; (build-zero-ancestor nil)
;; (build-zero-ancestor "/a/bc/d")

(defn extract-aggregations
  [query-map response]
  (let [aggs (:aggregations response)
        cats (-> aggs :category-path :buckets)
        cats (if cats cats (-> aggs :category-path :filter-cat-path :buckets))
        manuf (-> aggs :manufacturer-id :buckets)
        cat-path (:category-path query-map)
        cat-path-level (inc (slash-count cat-path))
        ;; cat-ancestors (flatten (map #(categories-at-level cats %) (range 0 cat-path-level)))
        cat-ancestors (when (< 0 cat-path-level)
                        (when-let [ancestors (flatten (map #(categories-at-level cats %) (range 1 cat-path-level)))]
                          (if (empty? ancestors)
                            (build-zero-ancestor cat-path)
                            ancestors
                            )))
        ]
    ;; (fprintln "cat:" cat-path-level (vec cat-ancestors))
    ;; (fprintln "query-map" query-map)
    {:category-path (categories-at-level cats cat-path-level)
     :category-path-ancestors cat-ancestors
     :manufacturer-id manuf}))

(defn extract-paging
  [query-map response]
  (let [num-per-page (-> (->long (-> query-map :num-per-page)) (min 100) (max 10))
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

(defn- select-matnrs [tokens]
  (let [upcs (remove nil? (map #(re-matches #"\d{1,10}" %) tokens))]
    (map #(let [n (- (count %) 12)] (subs % (max 0 n))) upcs)))
;; (stringify-seq (select-matnrs (string/split "1234567890 12345678901 abc 123456789012 def" #" ")))
;; (stringify-seq (select-upcs (string/split "abc def" #" ")))

(defn- select-upcs [tokens]
  (let [upcs (remove nil? (map #(re-matches #"\d{11,14}" %) tokens))]
    (map #(let [n (- (count %) 12)] (subs % (max 0 n))) upcs)))
;; (stringify-seq (select-upcs (string/split "12345678901 abc 123456789012 def" #" ")))
;; (stringify-seq (select-upcs (string/split "abc def" #" ")))

(def fields-to-search
  ;; ["name" "description" "category-name^0.1" "manufacturer-name^0.1" "product-class^0.1" "upc" "manufacturer-part-number" "summit-part-number" "matnr"])
  ["name" "description" "category-name^0.1" "manufacturer-name^0.1" "product-class^0.1"])

(defn build-filter
  "discover must/should/filtered components of query-map"
  [{:keys [service-center-id manufacturer-ids category-path] :as query-map}]
  {:pre [(validate-query-map query-map)]}
  (let [
        category-id (when category-path (-> (split category-path #"/") last))]
    (cond-> {:filtered [] :must [] :should [] :post-filter []}
      ;; manufacturer-ids  (update-in [:post-filter] conj {:terms {:manufacturer-id manufacturer-ids}})
      manufacturer-ids  (update-in [:post-filter] conj {:terms {:manufacturer-id manufacturer-ids}})
      service-center-id (update-in [:filtered] conj {:term {:service-center-ids service-center-id}})
      category-id       (update-in [:filtered] conj {:term {:category-ids category-id}})

      ;; service-center-count -- boost? filter?
      )))

(defn transform-search-query
  "discover must/should/filtered components of query-map"
  [{:keys [search service-center-id manufacturer-ids category-path] :as query-map}]
  {:pre [(validate-query-map query-map)]}
  (let [category-id (when category-path (-> (split category-path #"/") last))]
    (cond-> {:filtered [] :must [] :should [] :post-filter []}
      manufacturer-ids  (update-in [:post-filter] conj {:terms {:manufacturer-id manufacturer-ids}})
      service-center-id (update-in [:filtered] conj {:term {:service-center-ids service-center-id}})
      category-id       (update-in [:filtered] conj {:term {:category-ids category-id}})

      (not-empty search) (update-in [:must] conj
                                    {:multi_match {:query    (string/trim search)
                                                   :type     "cross_fields"
                                                   :operator "and"
                                                   ;; :minimum_should_match "75%"
                                                   :fields   fields-to-search
                                                   ;; :boost 50
                                                   }})
      )))

(defn build-search-word-query
  "build general word search (not upc/matnr/part#s) elasticsearch json query from query-map"
  [query-map]
  (let [q (transform-search-query query-map)
        post-filter? (not-empty (:post-filter q))]
         {:bool
          (cond-> {}
            (not-empty (:filtered q)) (assoc :filter (:filtered q))
            (not-empty (:should q)) (assoc :should (:should q))
            (not-empty (:must q)) (assoc :must   (:must q)))
          }
      ))
;; (build-search-query query-map)

(defn build-search-query
  "build elasticsearch json query from query-map"
  [query-map]
  (let [
        search-text (string/trim (:search query-map))
        paging (extract-paging query-map nil)
        do-aggs? (not (:total-hits-only query-map))
        general-word-query (build-search-word-query query-map)
        manufacturer-ids (:manufacturer-ids query-map)
        post-filter (if manufacturer-ids {:terms {:manufacturer-id manufacturer-ids}})
        filters (build-filter query-map)
        upcs (stringify-seq (select-upcs (string/split search-text #" ")))
        matnrs (stringify-seq (select-matnrs (string/split search-text #" ")))
        shoulds (remove nil?
                        [general-word-query
                         (when-not (string/blank? upcs) {:match {:upc {:query upcs :boost 10}}})
                         (when-not (string/blank? matnrs) {:match {:matnr {:query matnrs :boost 10}}})
                         {:match {:summit-part-number {:query search-text :boost 0.01}}}
                         {:match {:manufacturer-part-number {:query search-text :boost 0.01}}}
                         ]
                        )
        ]
    (cond->
        {
         :query
         {:bool
          {
           :filter (:filtered filters)
           :should shoulds
           :minimum_should_match 1}
          }
         :from (* (:num-per-page paging) (dec (:page-num paging)))
         :size (:num-per-page paging)
         :highlight
         {:pre_tags ["<span class=\"search-term\">"]
          :post_tags ["</span>"]
          :fragment_size 500
          :fields
          {:* {}}}
         }
    ;; do aggregations only on primary search.
      post-filter (assoc :post_filter post-filter)
      do-aggs? (assoc :aggregations
                    (cond->
                        {:manufacturer-id {:terms {:field "manufacturer-id" :min_doc_count 1 :size 100}}}
                      post-filter (assoc :category-path
                                         {:filter post-filter
                                          :aggs {:filter-cat-path {:terms {:field "category-path" :min_doc_count 1 :size 4000}}}})
                      (nil? post-filter) (assoc :category-path {:terms {:field "category-path" :min_doc_count 1 :size 4000}})
                      ))
     )
    )
  )


;; not currently used. Keeping around temporarily just in case ...
;; (defn ->url
;;   "create url string from query-map"
;;   [{:keys [search service-center-id manufacturer-ids category-id category-path] :as query-map}]
;;   (let [pieces
;;         (cond-> {}
;;           search            (assoc :search search)
;;           manufacturer-ids  (assoc :manufacturer (first manufacturer-ids))
;;           category-path     (assoc :category category-path)
;;           service-center-id (assoc :sc_filter service-center-id)
;;           )]
;;     pieces
;;     (str ; "https://www.summit.com/products?"
;;          (client/generate-query-string pieces))
;;     ))

;; (defn <-url
;;   "parse url into a query-map"
;;   [url]
;;   (let [tokens (split url #"&")
;;         params (map #(split % #"=") tokens)
;;         m      (into {} (map #(vector (-> % first keyword) (-> % second bidi/url-decode)) params))
;;         {:keys [search sc_filter manufacturer category]} m]
;;     (cond-> {}
;;       search       (assoc :search search)
;;       sc_filter    (assoc :service-center-id (read-string sc_filter))
;;       manufacturer (assoc :manufacturer-ids [(read-string manufacturer)])
;;       category     (assoc :category-path category)
;;       category     (assoc :category-id (-> category (split #"/") last read-string))
;;       )
;;     ))



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
