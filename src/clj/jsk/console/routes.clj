(ns jsk.console.routes
  (:require [compojure.core :refer [defroutes DELETE GET PUT POST]]
            [compojure.core :as cc]
            [compojure.route :as route]
            [ring.util.response :as rr]
            [cemerick.friend :as friend]
            [taoensso.timbre :as log]
            [jsk.common.job :as job]
            [jsk.common.agent :as agent]
            [jsk.common.db :as db]
            [jsk.common.util :as util]
            [jsk.common.workflow :as wf]
            [jsk.console.search :as search]
            [jsk.console.explorer :as explorer]
            [jsk.console.dashboard :as dashboard]
            [jsk.common.alert :as alert]
            [jsk.common.schedule :as schedule]))


;-----------------------------------------------------------------------
; This is from Chris Granger's lib-noir.
; Didn't need all of it.
;-----------------------------------------------------------------------
(defn- ->map [c]
  (if-not (map? c)
    {:body c}
    c))

(defn- set-headers
  "Add a map of headers to the given response. Headers must have string keys"
  [headers content]
  (update-in (->map content) [:headers] merge headers))

(defn- content-type
  "Wraps the response with the given content type and sets the body to the content."
  [ctype content]
  (set-headers {"Content-Type" ctype} content))

(defn edn-response
  "Wraps response in 'application/edn' content-type and calls
   pr-str on the Clojure data structure passed in."
  [data]
  (content-type "application/edn; charset=utf-8"
                (pr-str data)))

; put edn-response in middleware
;-----------------------------------------------------------------------
; Pulling user out of session.
;-----------------------------------------------------------------------
(defn- current-user [request]
  (get-in request [:session :jsk-user]))

;(defn- uid [request]
;  (-> request current-user :app-user-id))

; this is for development
(defn- uid [request] 2)


;-----------------------------------------------------------------------
; Default app routes.
;-----------------------------------------------------------------------
(defroutes app-routes
  (GET "/logout" req
    (friend/logout* (rr/redirect (str (:context req) "/"))))

  (GET "/logged-in/check" req
      (-> req current-user nil? not edn-response))

  (route/files "/" {:root "resources/public"})
  (route/resources "public")
  (route/not-found "Not found."))

;-----------------------------------------------------------------------
; Routes realted to jobs and job schedule associations.
;-----------------------------------------------------------------------
(defroutes job-routes
  (GET "/jobs" [_ :as request]
       (edn-response (job/ls-jobs)))

  (GET "/jobs/:id" [id]
       (-> id util/str->int job/get-job edn-response))

  (GET "/jobs/trigger-now/:id" [id]
       (-> id util/str->int job/trigger-now edn-response))

  (POST "/jobs/save" [_ :as request]
        (-> (:params request) (job/save-job! (uid request)) edn-response)))


;-----------------------------------------------------------------------
; Routes realted to schedules
;-----------------------------------------------------------------------
(defroutes schedule-routes
  (GET "/schedules" []
       (edn-response (schedule/ls-schedules)))

  (GET "/schedules/:id" [id]
       (-> id util/str->int schedule/get-schedule edn-response))

  (POST "/schedules/save" [_ :as request]
       (-> (:params request) (schedule/save-schedule! (uid request)) edn-response))

  (DELETE "/schedules/:id" [id :as request]
       (-> id util/str->int (schedule/rm-schedule! (uid request)) edn-response))

  (POST "/schedules/assoc" [_ :as request]
       (-> (:params request) (schedule/add-node-schedule-assoc!  (uid request)) edn-response))

  (DELETE "/schedules/assoc/:id" [id :as request]
       (-> (schedule/rm-node-schedule-assoc!  (util/str->int id) (uid request)) edn-response)))

;-----------------------------------------------------------------------
; Routes realted to alerts
;-----------------------------------------------------------------------
(defroutes alert-routes
  (GET "/alerts" []
       (edn-response (alert/ls-alerts)))

  (GET "/alerts/:id" [id]
       (-> id util/str->int alert/get-alert edn-response))

  (POST "/alerts/save" [_ :as request]
       (-> (:params request) (alert/save-alert! (uid request)) edn-response))

  (DELETE "/alerts/:id" [id :as request]
       (-> id util/str->int (alert/rm-alert! (uid request)) edn-response))

  (POST "/alerts/assoc" [_ :as request]
       (-> (:params request) (alert/add-node-alert-assoc!  (uid request)) edn-response))

  (DELETE "/alerts/assoc/:id" [id :as request]
       (-> (alert/rm-node-alert-assoc!  (util/str->int id) (uid request)) edn-response)))

;-----------------------------------------------------------------------
; Routes realted to agents
;-----------------------------------------------------------------------
(defroutes agent-routes
  (GET "/agents" []
       (edn-response (agent/ls-agents)))

  (GET "/agents/:id" [id]
       (-> id util/str->int agent/get-agent edn-response))

  (POST "/agents/save" [_ :as request]
        (-> (:params request) (agent/save-agent! (uid request)) edn-response))

  (DELETE "/agents/:id" [id :as request]
       (-> id util/str->int (agent/rm-agent! (uid request)) edn-response)))


;-----------------------------------------------------------------------
; Routes realted to workflows
;-----------------------------------------------------------------------
(defroutes workflow-routes
  (GET "/workflows" []
       (edn-response (wf/ls-workflows)))

  (GET "/workflows/:id" [id]
       (-> id util/str->int wf/get-workflow edn-response))

  (GET "/workflows/graph/:id" [id]
       (-> id util/str->int wf/workflow-nodes edn-response))

  (GET "/workflows/trigger-now/:id" [id]
       (-> id util/str->int wf/trigger-now edn-response))

  (POST "/workflows/save" [_ :as request]
        (-> (:params request) (wf/save-workflow! (uid request)) edn-response)))

;-----------------------------------------------------------------------
; Routes realted to execution data
;-----------------------------------------------------------------------
(defroutes execution-routes
  (GET "/executions/:id" [id]
       (-> id util/str->int db/get-execution-details edn-response))

  (GET "/executions/workflows/:id" [id]
       (-> id util/str->int db/get-execution-workflow-details edn-response))

  (POST "/executions/search/q" [_ :as request]
        (-> (:params request) search/executions edn-response))

  ; id is the execution id
  (GET "/executions/abort/:id" [id :as request]
       (-> id util/str->int (wf/abort-execution (uid request)) edn-response))

  (GET "/executions/pause/:id" [id :as request]
       (-> id util/str->int (wf/pause-execution (uid request)) edn-response))

  (GET "/executions/resume/:id" [id :as request]
       (-> id util/str->int (wf/resume-execution (uid request)) edn-response))

  ; id is the execution id, vid the vertex id
  (GET "/executions/force-success/:id/:vid" [id vid :as request]
       (let [execution-id (util/str->int id)
             vertex-id (util/str->int vid)]
         (edn-response (wf/force-success execution-id vertex-id (uid request)))))

  ; id is the execution id, vid the vertex id
  (GET "/executions/abort/:id/:vid" [id vid :as request]
       (let [execution-id (util/str->int id)
             vertex-id (util/str->int vid)]
         (edn-response (wf/abort-job execution-id vertex-id (uid request)))))

  ; id is the execution id, vid the vertex id
  (GET "/executions/pause/:id/:vid" [id vid :as request]
       (let [execution-id (util/str->int id)
             vertex-id (util/str->int vid)]
         (edn-response (wf/pause-job execution-id vertex-id (uid request)))))

  ; id is the execution id, vid the vertex id
  (GET "/executions/resume/:id/:vid" [id vid :as request]
       (let [execution-id (util/str->int id)
             vertex-id (util/str->int vid)]
         (edn-response (wf/resume-job execution-id vertex-id (uid request)))))

  (GET "/executions/restart/:id/:vid" [id vid :as request]
       (let [execution-id (util/str->int id)
             vertex-id (util/str->int vid)
             user-id (uid request)]
         (edn-response (wf/restart-execution execution-id vertex-id user-id)))))


;-----------------------------------------------------------------------
; Routes realted to nodes
;-----------------------------------------------------------------------
(defroutes node-routes
  (GET "/nodes" []
       (edn-response (db/ls-nodes)))

  (GET "/nodes/:id" [id]
       (-> id util/str->int db/get-node-by-id edn-response))

  (GET "/nodes/schedules/:id" [id]
       (-> id util/str->int db/node-schedule-associations edn-response))

  (GET "/nodes/alerts/:id" [id]
       (-> id util/str->int db/node-alert-associations edn-response)))


;-----------------------------------------------------------------------
; Routes realted to the explorer user interface
;-----------------------------------------------------------------------
(defroutes explorer-routes
  (GET "/explorer" []
       (edn-response (explorer/ls-directory)))

  (GET "/explorer/directory/:id" [id]
       (edn-response (explorer/ls-directory id)))

  (POST "/explorer/directory" [_ :as request]
       (-> request :params explorer/save-directory! edn-response))

  (DELETE "/explorer/directory/:id" [id :as request]
          (->> (uid request) (explorer/rm-directory! (util/str->int id)) edn-response))

  (POST "/explorer/directory/:dir-id/new-empty-job" [dir-id :as request]
       (edn-response (explorer/new-empty-job! (util/str->int dir-id) (uid request))))

  (POST "/explorer/directory/:dir-id/new-empty-workflow" [dir-id :as request]
        (edn-response (explorer/new-empty-workflow! (util/str->int dir-id) (uid request))))

  (POST "/explorer/new-empty-schedule" [_ :as request]
        (edn-response (explorer/new-empty-schedule! (uid request))))

  (POST "/explorer/new-empty-agent" [_ :as request]
        (edn-response (explorer/new-empty-agent! (uid request))))

  (POST "/explorer/new-empty-alert" [_ :as request]
        (edn-response (explorer/new-empty-alert! (uid request))))

  (PUT "/explorer/directory-change" [_ :as request]
       (-> request :params (explorer/change-parent-directory (uid request)) edn-response))

  (DELETE "/explorer/node/:id" [id :as request]
          (->> (uid request) (explorer/rm-node! (util/str->int id)) edn-response)))

;-----------------------------------------------------------------------
; Routes realted to the dashboard
;-----------------------------------------------------------------------
(defroutes dashboard-routes
  (GET "/dashboard" []
       (edn-response (dashboard/ls-elements)))

  (PUT "/dashboard/:node-id/enable" [node-id :as request]
       (->> (uid request) (dashboard/enable node-id) edn-response))

  (PUT "/dashboard/:node-id/disable" [node-id :as request]
       (->> (uid request) (dashboard/disable node-id) edn-response)))

;-----------------------------------------------------------------------
; Collection of all routes.
;-----------------------------------------------------------------------
(def all-routes (cc/routes agent-routes
                           schedule-routes
                           alert-routes
                           node-routes
                           job-routes
                           workflow-routes
                           explorer-routes
                           execution-routes
                           dashboard-routes
                           app-routes))
