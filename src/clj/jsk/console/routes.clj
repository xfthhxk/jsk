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

  (POST "/schedules/assoc" [_ :as request]
        (-> (:edn-params request) (schedule/assoc-schedules! (uid request)) edn-response)))

;-----------------------------------------------------------------------
; Routes realted to agents
;-----------------------------------------------------------------------
(defroutes agent-routes
  (GET "/agents" []
       (edn-response (agent/ls-agents)))

  (GET "/agents/:id" [id]
       (-> id util/str->int agent/get-agent edn-response))

  (POST "/agents/save" [_ :as request]
       (-> (:params request) (agent/save-agent! (uid request)) edn-response)))


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

  (GET "/executions/abort/:id" [id]
       (-> id util/str->int wf/abort-execution edn-response))

  (GET "/executions/resume/:id/:vid" [id vid]
       (let [execution-id (util/str->int id)
             vertex-id (util/str->int vid)]
         (edn-response (wf/resume-execution execution-id vertex-id)))))


;-----------------------------------------------------------------------
; Routes realted to nodes
;-----------------------------------------------------------------------
(defroutes node-routes
  (GET "/nodes" []
       (edn-response (db/ls-nodes)))

  (GET "/nodes/:id" [id]
       (-> id util/str->int db/get-node-by-id edn-response))

  (GET "/nodes/schedules/:id" [id]
       (-> id util/str->int db/schedule-ids-for-node edn-response)))



;-----------------------------------------------------------------------
; Collection of all routes.
;-----------------------------------------------------------------------
(def all-routes (cc/routes agent-routes
                           schedule-routes
                           node-routes
                           job-routes
                           workflow-routes
                           execution-routes
                           app-routes))
