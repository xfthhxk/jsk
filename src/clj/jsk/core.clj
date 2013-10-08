(ns jsk.core
  "JSK core"
  (:require [jsk.routes :as routes]
            [jsk.ps :as ps]
            [jsk.quartz :as q]
            [jsk.util :as ju]
            [jsk.user :as juser]
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

;-----------------------------------------------------------------------
; Friend authentication
;-----------------------------------------------------------------------
(defn- login-failure-handler [request]
  (error "login failed: " request))


(defn- friend-credential-fn [m]
  (info "friend-credential-fn input map is: " m)
  (if-let [app-user (juser/get-by-email (:email m))]
    (assoc m :jsk-user app-user)
    m))


(defn- friend-unauth-handler [request]
  (info "In unauth handler handler: " request))

(defn- make-friend-auth [routes]
  (friend/authenticate routes {:allow-anon? false
                               ; :unauthenticated-handler  friend-unauth-handler
                               :default-landing-uri "/index.html"
                               :login-uri "/login.html"
                               :login-failure-handler login-failure-handler
                               :workflows [(openid/workflow
                                              :openid-uri "/openid/login"
                                              :max-nonce-age (* 1000 60 5) ; 5 minutes in milliseconds
                                              :credential-fn friend-credential-fn)]}))

(def unauth-ring-response (-> ["Unauthenticated."] ju/make-error-response response/edn (rr/status 401)))

(defn- send-unauth-ring-response [msg app-user edn?]
  (warn "send-unauth-ring: " msg)
  (warn "app-user: " app-user)
  (warn "edn? " edn?)
  unauth-ring-response)

; friend will redirect, and the xhr will follow the redirect which eventually
; results in a 200 and shows the login page in the xhr response
; this gets to it before friend does and issues a 401 unauth
; which the client can handle
(defn- wrap-api-unauthenticated [handler]
  (fn[request]
    (let [app-user (-> request friend/current-authentication :jsk-user)
          edn? (ju/edn-request? request)]
      (if (and edn? (nil? app-user))
        (send-unauth-ring-response "Before calling handler" app-user edn?)
        (let [resp (handler request)
              status (:status resp)]
          (if (and edn? (= 302 status)) ; 302 is redirect ie for login
            (send-unauth-ring-response "After calling handler" app-user edn?)
            resp))))))


(defn- wrap-jsk-user-in-session [handler]
  (fn[request]
    (if-let [app-user (-> request friend/current-authentication :jsk-user)]
      (handler (assoc-in request [:session :jsk-user] app-user))
      (throw (Exception. "Unauthenticated request found! Bad middleware layering likely.")))))



; -- last item happens first
(def app (-> routes/all-routes
             redn/wrap-edn-params
             wrap-jsk-user-in-session
             make-friend-auth
             wrap-api-unauthenticated
             ch/site
             wrap-dir-index
             (wrap-resource "public")
             wrap-file-info
             wrap-exception))


(def war-handler app)
