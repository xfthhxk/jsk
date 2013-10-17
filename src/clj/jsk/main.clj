(ns jsk.main
  (:require [ring.adapter.jetty :as jetty]
            [jsk.handler :as jsk])
  (:gen-class))


(defn -main [& args]
  (jsk/init)
  (jetty/run-jetty #'jsk/app {:configurator jsk/ws-configurator
                              :port 8080
                              :join? false}))
