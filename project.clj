(defproject haystack "0.1.0-SNAPSHOT"
  :description "ecommerce search service in elasticsearch"
  :url "http://murphydye.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clj-http "3.4.1"]
                 [clojurewerkz/elastisch "3.0.0-beta1"]

                 [bidi "2.0.14"]
                 [aleph "0.4.1"]
                 [yada "1.1.46"]

                 ;; these are temporary. eventually will call an ecommerce service for this data
                 [mysql/mysql-connector-java "5.1.6"]
                 [org.clojure/java.jdbc "0.3.7"]
                 ]
  :main ^:skip-aot haystack.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
