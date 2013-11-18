(ns jsk.handler
  "JSK handler"
  (:require
            [jsk.conf :as conf]
            [jsk.conductor :as conductor]
            [jsk.db :as db]
            [jsk.routes :as routes]
            [jsk.ps :as ps]
            [jsk.quartz :as q]
            [jsk.util :as ju]
            [jsk.user :as juser]
            [jsk.job :as job]
            [jsk.execution :as execution]
            [jsk.schedule :as schedule]
            [jsk.notification :as n]
            [cemerick.friend :as friend]
            [cemerick.friend.openid :as openid]
            [compojure.handler :as ch]
            [ring.middleware.edn :as redn]
            [ring.middleware.reload :as reload]
            [ring.util.response :as rr]
            [com.keminglabs.jetty7-websockets-async.core :refer [configurator]]
            [clojure.core.async :refer [chan go go-loop put! >! <!]]
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal)]
            [com.postspectacular.rotor :as rotor])
  (:use [swiss-arrows core]
        [ring.middleware.session.memory :only [memory-store]]
        [ring.middleware.resource :only [wrap-resource]]
        [ring.middleware.file-info :only [wrap-file-info]]))



; web socket clients, output channels
(def ws-clients (atom #{}))

; As clients connect they are added to this channel
; how do we know when they disconnect.
(def ws-connect-channel (chan))

; Channel used to communicate with the conductor.
(def conductor-channel (chan))

; Channel used to communicate events for clients (users etc.)
(def info-channel (chan))

(defn notify-error [{:keys [job-name execution-id error]}]
  (if error
    (let [to (conf/error-email-to)
          subject (str "[JSK ERROR] " job-name)
          body (str "Job execution ID: " execution-id "\n\n" error)]
      (info "Sending error email for execution: " execution-id)
      (n/mail to subject body))))

(defn broadcast-execution [data]
  (doseq [c @ws-clients]
    (put! c (pr-str data))))


(defn- setup-job-execution-recorder []

  (execution/register-event-channels! info-channel conductor-channel)

  (q/register-job-execution-recorder!
    (execution/make-job-recorder "JSK-Job-Execution-Listener"))

  (go-loop [exec-map (<! info-channel)]
    (debug "Read from execution event channel: " exec-map)
    (broadcast-execution exec-map)
    (notify-error exec-map)
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
; Read jobs from database and creates them in quartz.
;-----------------------------------------------------------------------
(defn- populate-quartz-jobs []
  (let [jj (job/ls-jobs)]
    (info "Setting up " (count jj) " jobs in Quartz.")
    (doseq [j jj]
      (q/save-job! j))))

;-----------------------------------------------------------------------
; Read schedules from database and associates them to jobs in quartz.
;-----------------------------------------------------------------------
(defn- populate-quartz-triggers []
  (let [ss (schedule/enabled-jobs-schedule-info)
        ss-by-job (group-by :job-id ss)]
    (info "Setting up " (count ss) " triggers in Quartz.")
    (doseq [job-id (keys ss-by-job)]
      (q/schedule-cron-job! job-id (ss-by-job job-id)))))

(defn- populate-quartz []
  (populate-quartz-jobs)
  (populate-quartz-triggers))


;-----------------------------------------------------------------------
; App starts ticking here.
;-----------------------------------------------------------------------
(defn init []
  "init will be called once when the app is deployed as a servlet
   on an app server such as Tomcat"

  (init-logging)
  (conf/init "conf/jsk-conf.clj")
  (conf/init-db)

  (info "Ensuring log directory exists at: " (conf/exec-log-dir))
  (ju/ensure-directory (conf/exec-log-dir))


  (n/init)
  (info "Notifications initialized.")

  (conductor/init conductor-channel info-channel)

  (q/init conductor-channel)
  (info "Quartz initialized.")

  (setup-job-execution-recorder)
  (init-ws)
  (info "Job execution tracking setup.")


  (populate-quartz)
  (info "Quartz populated.")

  (q/start)
  (info "Quartz started.")

  (info "JSK started successfully."))

;-----------------------------------------------------------------------
; App shutdown procedure.
;-----------------------------------------------------------------------
(defn destroy []
  "destroy will be called when the app is shut down"

  (info "JSK is shutting down...")
  (q/stop)
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

(def unauth-ring-response (-> ["Unauthenticated."] ju/make-error-response routes/edn-response (rr/status 401)))

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
             ; wrap-jsk-user-in-session
             ; make-friend-auth
             ; wrap-api-unauthenticated
             ch/site
             wrap-dir-index
             (wrap-resource "public")
             wrap-file-info
             wrap-exception))

