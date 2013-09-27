(ns jsk.handler
  (:require [compojure.core :refer [defroutes GET PUT POST]]
            [compojure.route :as route]
            [ring.middleware.edn :as redn]
            [noir.util.middleware :as middleware]
            [noir.response :as response]
            [ring.middleware.stacktrace :as rs]
            [ring.middleware.resource :as rr]
            [taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal)]
            [com.postspectacular.rotor :as rotor]
            [jsk.core :as q]
            [jsk.db :as db]))


(defn str->int [params k]
  (assoc params k (Integer/parseInt (k params))))

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
       (response/edn (db/ls-schedules)))

  (GET "/schedules/:id" [id]
       (response/edn (db/get-schedule id)))

  (POST "/schedules/save" [_ :as r]
        (info r)
        (db/save-schedule! (str->int (:params r) :schedule-id))))
        ;(db/save-schedule! (Integer/parseInt schedule-id) schedule-name schedule-desc cron-expression "amar")))

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
          :formats [:edn]
          :access-rules []))      ; add access rules here. each rule is a vector


;(def war-handler (middleware/war-handler app))
(def war-handler
  (-> app (middleware/war-handler)
          (redn/wrap-edn-params)
          (rr/wrap-resource "public")))
