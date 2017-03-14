(ns haystack.ecommerce
  (:require [clojure.java.jdbc :as j]
            [clojure.string :refer [split]]
            [clojure.data.csv :as csv]
            ))

(def ^:private categories-atom (atom nil))
(def ^:private manufacturers-atom (atom nil))

(defn clear-empty
  "remove key/values where the value is empty (nil or null string)"
  [m]
  (into {}
        (remove (fn [[k v]] (or (nil? v) (= v ""))) m)))
;; (clear-empty {:a 3 :b nil :c 2 :d ""})

(def db-map (atom {:subprotocol "mysql"
                   :subname (or (System/getenv "DB_HOST") "//localhost:3306/blue_harvest_dev")
                   :user (or (System/getenv "DB_USER") "user")
                   :password (or (System/getenv "DB_PW") "pw")}))

(defn as-matnr
  [s]
  (let [s (str s)
        zeroed (str "000000000000000000" s)]
    (subs zeroed (count s))))

(defn write-step
  [data]
  (let [matnr (as-matnr (first data))
        title (nth data 2)]
    (j/update! @db-map :products {:step_title title} ["matnr = ?" matnr])
    ))
;; (write-step ["000000000000001425" :old-title "new title"])
;; (j/query @db-map ["select * from products where matnr = ?" (as-matnr 1425)])

(defn process-step-file
  [f]
  (with-open [in (clojure.java.io/reader "/home/bmd/Downloads/step-titles.csv")]
    (doall
     (map f (csv/read-csv in)))))
;; (process-step-file write-step)



(def product-query-count-sql
"SELECT
count(distinct products.id)
FROM `products`
LEFT JOIN `external_files`
  ON (`products`.`id` = `external_files`.`product_id` and `external_files`.`type` = 'image')
LEFT JOIN `categories_products`
ON (`products`.`id` = `categories_products`.`product_id`)
LEFT JOIN `solr_categories`
ON (`categories_products`.`category_id` = `solr_categories`.`category_id`)
LEFT JOIN `product_classes`
ON (`products`.`product_class_id` = `product_classes`.`id`)
LEFT JOIN `solr_service_centers`
ON (`products`.`id` = `solr_service_centers`.`product_id`)
LEFT JOIN `manufacturers`
ON (`products`.`manufacturer_id` = `manufacturers`.`id`)
LEFT JOIN `categories`
ON (`categories`.`id` = `categories_products`.`category_id`)
LEFT JOIN
(
 SELECT `category_hierarchies`.`ancestor_id`
 FROM `category_hierarchies`
 GROUP BY 1
 HAVING MAX(`category_hierarchies`.`generations`) = 0
 )
AS `leaves`
ON (`categories`.`id` = `leaves`.`ancestor_id`)
INNER JOIN
(
 SELECT MAX(`stock_statuses`.`product_id`) as `product_id`, SUM(`stock_statuses`.`eod_qty`) as `eod_qty`
 FROM `stock_statuses`
 GROUP BY `stock_statuses`.`product_id`
 )
AS `ss`
ON (`products`.`id` = `ss`.`product_id`)
;
")

  ;; WHERE `external_files`.`type` = 'image'



(def product-query-sql
"SELECT
now() AS 'updated-at',
'product' AS `_type`,
`products`.`id` AS `bh-product-id`,
`products`.`manufacturer_id` AS `manufacturer-id`,
`products`.`product_class_id` AS `product-class-id`,
`categories_products`.`category_id` AS `category-id`,
`categories`.`parent_id` AS `category-parent-id`,
`search_categories`.`category_ids` AS `category-ids`,
`search_categories`.`category_path` AS `category-path`,
`search_service_centers`.`service_center_ids` AS `service-center-ids`,
IFNULL(`products`.`step_title`,`products`.`name`) AS `name`,
IFNULL(`products`.`step_description`,`products`.`long_description`) AS `description`,
`products`.`upc` AS `upc`,
`manufacturers`.`name` AS `manufacturer-name`,
`products`.`manufacturer_part_number` AS `manufacturer-part-number`,
`products`.`summit_part_number` AS `summit-part-number`,
`product_classes`.`name` AS `product-class`,
`categories`.`name` AS `category-name`,
`products`.`matnr` AS `matnr`,
`external_files`.`url` AS `image-url`,
(`ss`.`eod_qty` > 0) AS `service-center-count`
FROM `products`
LEFT JOIN `external_files`
  ON (`products`.`id` = `external_files`.`product_id` and `external_files`.`type` = 'image')
LEFT JOIN `categories_products`
  ON (`products`.`id` = `categories_products`.`product_id`)
LEFT JOIN
(
 SELECT `solr_category_ancestors`.`descendant_id` AS `category_id`,
   group_concat(`categories`.`id`
      order by `solr_category_ancestors`.`generations` DESC
      separator ',')
      AS `category_ids`,
   concat('/',
      group_concat(`categories`.`id`
         order by `solr_category_ancestors`.`generations` DESC
         separator '/'))
      AS `category_path`
 FROM ((`category_hierarchies` `solr_category_ancestors`
  join `category_hierarchies` `solr_category_descendants`
    on ((`solr_category_ancestors`.`descendant_id` = `solr_category_descendants`.`ancestor_id`)))
  join `categories`
    on((`solr_category_ancestors`.`ancestor_id` = `categories`.`id`)))
  where (`solr_category_descendants`.`generations` = 0)
  group by `solr_category_descendants`.`descendant_id`
) AS `search_categories`
  ON (`categories_products`.`category_id` = `search_categories`.`category_id`)

LEFT JOIN
(
 SELECT `stock_statuses`.`product_id` AS `product_id`,
     group_concat(`stock_statuses`.`service_center_id` separator ',') AS `service_center_ids`
 FROM `stock_statuses`
 where (`stock_statuses`.`eod_qty` > 0)
 group by `stock_statuses`.`product_id`
) AS `search_service_centers`
  ON (`products`.`id` = `search_service_centers`.`product_id`)

LEFT JOIN `product_classes`
  ON (`products`.`product_class_id` = `product_classes`.`id`)
LEFT JOIN `manufacturers`
  ON (`products`.`manufacturer_id` = `manufacturers`.`id`)
LEFT JOIN `categories`
  ON (`categories`.`id` = `categories_products`.`category_id`)

LEFT JOIN
(
 SELECT `category_hierarchies`.`ancestor_id`
 FROM `category_hierarchies`
 GROUP BY 1
 HAVING MAX(`category_hierarchies`.`generations`) = 0
 ) AS `leaves`
  ON (`categories`.`id` = `leaves`.`ancestor_id`)

INNER JOIN
(
 SELECT `stock_statuses`.`product_id` as `product_id`, SUM(`stock_statuses`.`eod_qty`) as `eod_qty`
 FROM `stock_statuses`
 GROUP BY `stock_statuses`.`product_id`
 ) AS `ss`
  ON (`products`.`id` = `ss`.`product_id`)
;")

;; (println "\n\n" product-query-sql)

(defn clean-product-plus [m]
  (cond-> m
    true clear-empty

    (not-empty (:service-center-ids m))
    (assoc :service-center-ids
           (map read-string (split (:service-center-ids m) #",")))

    (not-empty (:category-ids m))
    (assoc :category-ids
           (map read-string (split (:category-ids m) #",")))

    (not-empty (:matnr m))
    (assoc :matnr
           (->> m :matnr (re-find #"0*(\d+)") last read-string str))
    ))

(defn products-plus []
  (map clean-product-plus (j/query @db-map [product-query-sql])))

;; (defn products []
;;   (j/query @db-map ["select * from products limit 1"]))

;; (defn manufacturers []
;;   (j/query @db-map ["select * from manufacturers"]))

(defn- get-categories
  "return jdbc result set"
  []
  (let [sql "select c.id, c.parent_id, c.name, solr.category_path
from categories c
join solr_categories solr on solr.category_id=c.id"]
    (j/query @db-map [sql])))

(defn- build-categories
  "return hash of key=category-id, value={:id :name :parent-id :path}"
  []
  (let [result (get-categories)]
    (into {}
          (map (fn [c] [(:id c) c]) result))))

(defn- build-manufacturers
  "return hash of key=category-id, value={:id :name :parent-id :path}"
  []
  (let [result (j/query @db-map ["select id, name, image_data 'image-data' from manufacturers"])]
    (into {}
          (map (fn [m] [(:id m) m]) result))))

(defn- categories
  []
  (when-not @categories-atom
    (reset! categories-atom (build-categories)))
  @categories-atom)

(defn- manufacturers
  []
  (when-not @manufacturers-atom
    (reset! manufacturers-atom (build-manufacturers)))
  @manufacturers-atom)

(defn find-category-by-path
  "find category for given path"
  [path]
  ((categories) (-> (split path #"/") last read-string)))

(defn find-category [id]
  ((categories) id))

(defn find-manufacturer [id]
  ((manufacturers) id))

;; (find-category 1)
;; (find-category-by-path "/390/1")
;; (find-manufacturer 1)

