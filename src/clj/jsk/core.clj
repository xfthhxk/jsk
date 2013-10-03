(ns jsk.core
  "JSK core"
  (:require [jsk.routes :as routes]
            [jsk.ps :as ps]
            [jsk.quartz :as q]
            [ring.middleware.edn :as redn]
            [noir.util.middleware :as middleware]
            [noir.response :as response]
            [ring.util.response :as rr]
            [clojurewerkz.quartzite.scheduler :as qs]
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal)]
            [com.postspectacular.rotor :as rotor]))


;  (let [date-job (q/make-shell-job "date")
;        cal-job (q/make-shell-job "cal" [[1] [2012]])
;        trigger (q/make-trigger "triggers.1")]
;    (qs/schedule cal-job (q/make-trigger "triggers.1"))
;    (qs/schedule date-job (q/make-trigger "triggers.2")))


;-----------------------------------------------------------------------
; Logging initalizer.
;-----------------------------------------------------------------------
(defn- init-logging []
  "Setup logging options"
  (timbre/set-config! [:appenders :rotor]
                      {:min-level :info
                       :enabled? true
                       :async? false                  ; should always be false for rotor
                       :max-message-per-msecs nil
                       :fn rotor/append})

  (timbre/set-config! [:shared-appender-config :rotor]
                      {:path "./log/jsk.log"
                       :max-size (* 512 1024)
                       :backlog 5}))


;-----------------------------------------------------------------------
; App starts ticking here.
;-----------------------------------------------------------------------
(defn init []
  "init will be called once when the app is deployed as a servlet
   on an app server such as Tomcat"

  (init-logging)
  (qs/initialize)
  (qs/start)
  (info "JSK started successfully."))

;-----------------------------------------------------------------------
; App shutdown procedure.
;-----------------------------------------------------------------------
(defn destroy []
  "destroy will be called when the app is shut down"

  (info "JSK is shutting down...")
  (qs/shutdown)
  (info "JSK has stopped."))


;-----------------------------------------------------------------------
; Exception handling middleware.
; Client communicates via XHRs using EDN, don't want to send back
; a huge stack trace. This logs the error and sends back a 500 response
; with the error message.
;-----------------------------------------------------------------------
(defn- wrap-exception [handler]
  (fn[request]
    (try
      (handler request)
      (catch Exception ex
        (error ex)
        (-> (.getMessage ex) response/edn (rr/status 500))))))

(def app (middleware/app-handler routes/all-routes))

(def war-handler
  (-> app (middleware/war-handler)
          wrap-exception
          (redn/wrap-edn-params)))
