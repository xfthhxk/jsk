(ns jsk.handler
  "JSK handler"
  (:require
            [jsk.conf :as conf]
            [jsk.routes :as routes]
            [jsk.util :as ju]
            [jsk.user :as juser]
            [cemerick.friend :as friend]
            [cemerick.friend.openid :as openid]
            [compojure.handler :as ch]
            [ring.middleware.edn :as redn]
            [ring.middleware.reload :as reload]
            [ring.util.response :as rr]
            [com.keminglabs.jetty7-websockets-async.core :refer [configurator]]
            [clojure.core.async :refer [chan go go-loop put! >! <!]]
            [taoensso.timbre :as log])
  (:use [ring.middleware.session.memory :only [memory-store]]
        [ring.middleware.resource :only [wrap-resource]]
        [ring.middleware.file-info :only [wrap-file-info]]))



; web socket clients, output channels
(def ws-clients (atom #{}))

; As clients connect they are added to this channel
; how do we know when they disconnect.
(def ws-connect-channel (chan))


; Channel used to communicate events for clients (users etc.)
(def info-channel (chan))

(defn broadcast-execution [data]
  (doseq [c @ws-clients]
    (put! c (pr-str data))))


; FIXME: this needs to listen to conductor's published
;        notifications and fwd to web sockets
(defn- setup-job-execution-recorder []

  ; (execution/register-event-channels! info-channel conductor-channel)

  ; (q/register-job-execution-recorder!
  ; (execution/make-job-recorder "JSK-Job-Execution-Listener"))

  (go-loop [exec-map (<! info-channel)]
    (log/debug "Read from execution event channel: " exec-map)
    (broadcast-execution exec-map)
    (recur (<! info-channel))))


(def ws-configurator
  (configurator ws-connect-channel {:path "/executions"}))

(defn init-ws []
  (go-loop []
    (let [{:keys[in out] :as ws-req} (<! ws-connect-channel)]
      ; (info "read off of ws-socket-channel: " ws-req)
      (swap! ws-clients conj in)
      ;(>! in (pr-str {:greeting "hello"}))
      (recur))))



;-----------------------------------------------------------------------
; App starts ticking here.
;-----------------------------------------------------------------------
(defn init []
  "init will be called once when the app is deployed as a servlet
   on an app server such as Tomcat"

  (log/info "JSK web app beginning init.")
  (log/info "Connecting to database.")
  (conf/init-db)

  (log/info "Initializing websockets.")
  (init-ws)

  (log/info "JSK web app started successfully."))

;-----------------------------------------------------------------------
; App shutdown procedure.
;-----------------------------------------------------------------------
(defn destroy []
  "destroy will be called when the app is shut down")



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
        (log/error ex)
        (-> [(.getMessage ex)] ju/make-error-response routes/edn-response (rr/status 500))))))

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
  (log/error "login failed: " request))


(defn- friend-credential-fn [m]
  (log/info "friend-credential-fn input map is: " m)
  (if-let [app-user (juser/get-by-email (:email m))]
    (assoc m :jsk-user app-user)
    m))


(defn- friend-unauth-handler [request]
  (log/debug "In unauth handler handler")
  (rr/redirect (str (:context request) "/login.html")))

(defn- make-friend-auth [routes]
  (friend/authenticate routes {:allow-anon? false
                               :unauthenticated-handler  friend-unauth-handler
                               :default-landing-uri "/index.html"
                               :login-uri "/login.html"
                               :login-failure-handler login-failure-handler
                               :workflows [(openid/workflow
                                              :openid-uri "/openid/login"
                                              :max-nonce-age (* 1000 60 5) ; 5 minutes in milliseconds
                                              :credential-fn friend-credential-fn)]}))

(def unauth-ring-response (-> ["Unauthenticated."] ju/make-error-response routes/edn-response (rr/status 401)))

(defn- send-unauth-ring-response [msg app-user edn?]
  (log/warn "send-unauth-ring: " msg)
  (log/warn "app-user: " app-user)
  (log/warn "edn? " edn?)
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
             ;wrap-jsk-user-in-session
             ;make-friend-auth
             ;wrap-api-unauthenticated
             ch/site
             wrap-dir-index
             (wrap-resource "public")
             wrap-file-info
             wrap-exception))

