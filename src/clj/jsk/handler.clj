(ns jsk.handler
  (:require [compojure.core :refer [defroutes GET PUT POST]]
            [compojure.route :as route]
            [noir.util.middleware :as middleware]
            [noir.response :as response]
            [ring.middleware.stacktrace :as rs]
            [ring.middleware.resource :as rr]
            [taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal)]
            [com.postspectacular.rotor :as rotor]
            [jsk.core :as q]
            [jsk.db :as db]))

(defroutes app-routes
  (route/resources "/")
  (route/not-found "Not found."))

;(defroutes job-routes
;  (GET "/jobs" []
;       (vj/jobs-fn)))
;  (POST "/jobs/add" [:as {params :params}]
;        (vj/add-job! params)))

;(defroutes trigger-routes
;  (GET "/triggers" []
;       (vt/triggers-fn)))

(defroutes schedule-routes
  (GET "/schedules" []
       (response/edn (db/ls-schedules))))
;  (GET "/schedules/add" req []
;       (info "in schedules/add")
;       (vs/show-add-schedule (:params req)))
;  (POST "/schedules/add" [:as {params :params}]
;       (info "in POST schedules/add")
;       (vs/add-schedule! params)))


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
          [schedule-routes app-routes] ; add app routes here
          :middleware [rs/wrap-stacktrace]          ; add custom middleware here
          :access-rules []))      ; add access rules here. each rule is a vector


;(def war-handler (middleware/war-handler app))
(def war-handler
  (-> app (middleware/war-handler)
          (rr/wrap-resource "public")))
