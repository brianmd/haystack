(ns haystack.core
  (:require [haystack.routes :as routes]
            )
  (:gen-class))

(defn -main
  [& args]
  (println "starting server")
  (routes/restart-server)
  (println "server started")
  (loop []
    (Thread/sleep 100000)
    (recur))
  )

(println "\n\nto run server: (routes/restart-server)\n\n")
