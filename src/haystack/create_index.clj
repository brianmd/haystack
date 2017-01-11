(ns haystack.create-index
  (:require  [clojure.test :as t]
             [clojurewerkz.elastisch.native :as es]
             [clojurewerkz.elastisch.rest       :as esr]
             [clojurewerkz.elastisch.rest.index :as esi]
             [clojurewerkz.elastisch.rest.bulk  :as esrb]
             [clojurewerkz.elastisch.rest.document :as esd]
             [clojurewerkz.elastisch.query         :as q]
             [clojurewerkz.elastisch.rest.response :as esrsp]
             [clojurewerkz.elastisch.common.bulk   :as bulk]
             [clojure.set :as set]
             [clojure.pprint :as pp]

             [haystack.ecommerce :as ecommerce]
             [haystack.query :as query]
             ;; [com.murphydye/mishmash :as mm]
             ;; [summit.utils.core :refer :all]
             ;; [summit.db.relationships :as db]
             ))

(def ecommerce-mapping-types
  (let [;; analyzers
        ;; snowball {:type "string" :analyzer "snowball" :store true}
        ;; snowball {:type "string" :analyzer "snowball" :index_options "offsets" :store true}
        snowball {:type "string" :analyzer "snowball"}
        part-num {:type "string" :analyzer "part-num-analyzer"}
        ;; part-num {:type "string"}
        string {:type "string"}
        integer {:type "integer"}
        long {:type "long"}
        ignore-int {:type "integer" :include_in_all false}
        path {:type "string" :analyzer "path-analyzer"}
        ;; path {:type "string" :index "not_analyzed"}
        ;; path {:type "string"}
        date {:type "date", :format "strict_date_optional_time||epoch_millis"}
        upc {:type "string" :analyzer "upc-analyzer"}

        ;; standard fields
        name {:name snowball}
        descript {:description snowball}
        ]
    (println "should have 1 shard")
    {:settings
     {:number_of_shards 1
      :number_of_replicas 1
      :analysis
      {
       :filter
       {:scrunch {:type "word_delimiter"
                  :generate_word_parts "false"
                  :generate_number_parts "false"
                  :split_on_numerics "false"
                  :split_on_case_change "false"
                  :preserve_original "false"
                  :catenate_all true}
        :part-num-ngram {:type "nGram"
                         :min_gram 3
                         :max_gram 15}}

       :tokenizer
       {:path-tokenizer {:type "path_hierarchy"
                         :delimiter "/"}
        :upc-tokenizer {:type "ngram" :min_gram 11 :max_gram 12}
        ;; :part-num-tokenizer {:type "ngram" :min_gram 3 :max_gram 8}
        }

       :analyzer
       {:path-analyzer {:type "custom"
                        :tokenizer "path-tokenizer"
                        :filter "lowercase"}
        :upc-analyzer {:type "custom"
                       :tokenizer "upc-tokenizer"}
        :part-num-analyzer {:type "custom"
                            :tokenizer "keyword"
                            ;; :tokenizer "part-num-tokenizer"
                            :filter ["scrunch"  "lowercase" "part-num-ngram"]}
                            ;; :filter ["scrunch"  "lowercase"]}
        }}}

     :mappings
     {
      "productplus"
      {:properties
       (merge name descript
              {:category-name snowball
               :manufacturer-name snowball
               :product-class snowball
               :matnr {:type "string"}
               ;; :matnr {:type "string" :tokenizer "keyword"}
               ;; :matnr long
               ;; :matnr {:type "string" :analyzer "not_analyzed" :search_analyzer "not_analyzed"}

               :manufacturer-part-number part-num
               :summit-part-number part-num

               :category-id integer
               :category-ids ignore-int
               :category-path path
               :service-center-ids ignore-int
               :service-center-count ignore-int
               :upc upc
               :updated-at date

               :bh-product-id ignore-int
               :product-class-id ignore-int
               :manufacturer-id ignore-int
               })}}
       }))

(defn bulk-create-all
  "adds all docs in one shot"
  [repo index-name type-name docs]
  (esrb/bulk
   repo
   (esrb/bulk-index (map #(assoc %
                                 :_index index-name
                                 :_type type-name
                                 :_id (:id %))
                         docs))))

(defn bulk-create
  "add docs in groups of 1000"
  [repo index-name type-name docs]
  (map #(bulk-create-all repo index-name type-name %) (partition 1000 1000 nil docs)))

(defn reload
  [repo index-name]
  (println "reloading (delete index, create index, bulk-create documents)")
  (esi/delete repo index-name)
  (println "deleted")
  (try
    (esi/create repo index-name ecommerce-mapping-types)
    (catch Throwable e
      (println "error in index create")
      (println e)
      (throw e)))
  (println "loading data")
  (doall
   (bulk-create repo index-name "productplus"
                (->> (ecommerce/products-plus) ))
   )
  (println "finished reloading")
  )



;; ------------------------------ Elasticsearch response functions

(defn hit-count [response]
  (-> response :hits :total))

(defn hits [response]
  (-> response :hits :hits))


(defn find-id [repo index-name type id]
  (esd/get repo index-name type id))
;; (find-id repo index-name "solr_categories" 33)

(defn count-for-type
  ;; ([type] (count-for-type index-name type))
  ;; ([index-name type] (count-for-type repo index-name type))
  ([repo index-name type]
   (hit-count
    (esd/search repo index-name type {:query
                                      {:filtered  ;; allows query results to be cached
                                       {:query
                                        {:match_all {}}}}
                                      :size 0}))))


