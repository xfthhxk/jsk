(ns jsk.handler
  (:require [compojure.core :refer [defroutes]]
            [compojure.route :as route]
            [noir.util.middleware :as middleware]
            [taoensso.timbre :as timbre]
            [com.postspectacular.rotor :as rotor]
            [jsk.core :as q]))

(defroutes app-routes
  (route/resources "/")
  (route/not-found "Not found."))


(defn init
  "init will be called once when the app is deployed as a servlet
   on an app server such as Tomcat"
  []

  (timbre/set-config!
   [:appenders :rotor]
   {:min-level :info
    :enabled? true
    :async? false                  ; should always be false for rotor
    :max-message-per-msecs nil
    :fn rotor/append})

  (timbre/set-config!
   [:shared-appender-config :rotor]
   {:path "./log/jsk.log" :max-size (* 512 1024) :backlog 5})

  (q/init)

  (timbre/info "JSK started successfully."))



(defn destroy
  "destroy will be called when the app is shut down"
  []
  (timbre/info "JSK is shutting down...")
  (q/shutdown)
  (timbre/info "JSK has stopped."))


(def app (middleware/app-handler
          [app-routes]            ; add app routes here
          :middleware []          ; add custom middleware here
          :access-rules []))      ; add access rules here. each rule is a vector


(def war-handler (middleware/war-handler app))
