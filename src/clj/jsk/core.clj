(ns jsk.core
  "JSK core"
  (:require [jsk.routes :as routes]
            [jsk.ps :as ps]
            [jsk.quartz :as q]
            [jsk.util :as ju]
            [cemerick.friend :as friend]
            [cemerick.friend.openid :as openid]
            [compojure.handler :as ch]
            [ring.middleware.edn :as redn]
            ; [noir.util.middleware :as middleware]
            [noir.response :as response]
            [ring.util.response :as rr]
            [clojurewerkz.quartzite.scheduler :as qs]
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal)]
            [com.postspectacular.rotor :as rotor])
  (:use [swiss-arrows core]
        [ring.middleware.session.memory :only [memory-store]]
        [ring.middleware.resource :only [wrap-resource]]
        [ring.middleware.file-info :only [wrap-file-info]]))


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
        (-> [(.getMessage ex)] ju/make-error-response response/edn (rr/status 500))))))

;-----------------------------------------------------------------------
; Serve up index.html when nothing specified.
;-----------------------------------------------------------------------
(defn- wrap-dir-index [handler]
  (fn[request]
    (handler (update-in request [:uri] #(if (= "/" %1) "/index.html" %1)))))

(defn- login-failure-handler [request]
  (error "login failed: " request))

(defn- make-friend-auth [routes]
  (friend/authenticate routes {:allow-anon? false
                               :default-landing-uri "/index.html"
                               :login-uri "/login.html"
                               :login-failure-handler login-failure-handler
                               :workflows [(openid/workflow
                                              :openid-uri "/openid/login"
                                              :max-nonce-age (* 1000 60 60) ; in milliseconds
                                              :credential-fn identity)]}))

(defn- wrap-api-unauthenticated [handler]
  (fn[request]
    (if (and (ju/edn-request? request)
             (-> request friend/identity nil?))
      (-> ["Unauthenticated."] ju/make-error-response response/edn (rr/status 401))
      (handler request))))

;(def app (middleware/app-handler routes/all-routes))

; -- last item happens first
(def app (-> routes/all-routes
             redn/wrap-edn-params
             make-friend-auth
             wrap-dir-index
             wrap-api-unauthenticated
             ch/site
             (wrap-resource "public")
             wrap-file-info
             wrap-exception))


(def war-handler app)
;  (-> app (middleware/war-handler)
;          wrap-dir-index
;          wrap-exception
;          (redn/wrap-edn-params)))
