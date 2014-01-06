(ns jsk.routes
  (:require [compojure.core :refer [defroutes DELETE GET PUT POST]]
            [compojure.core :as cc]
            [compojure.route :as route]
            [ring.util.response :as rr]
            [cemerick.friend :as friend]
            [taoensso.timbre :as timbre :refer (info warn error)]
            [jsk.job :as j]
            [jsk.db :as db]
            [jsk.util :as u]
            [jsk.conductor :as conductor]
            [jsk.workflow :as w]
            [jsk.quartz :as q]
            [jsk.search :as search]
            [jsk.schedule :as s]))


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
       (edn-response (j/ls-jobs)))

  (GET "/jobs/:id" [id]
       (-> id u/str->int j/get-job edn-response))

  (GET "/jobs/trigger-now/:id" [id]
       (-> id u/str->int conductor/trigger-job-now edn-response))

  (POST "/jobs/save" [_ :as request]
        (-> (:params request) (j/save-job! (uid request)) edn-response)))

;-----------------------------------------------------------------------
; Routes realted to schedules
;-----------------------------------------------------------------------
(defroutes schedule-routes
  (GET "/schedules" []
       (edn-response (s/ls-schedules)))

  (GET "/schedules/:id" [id]
       (-> id u/str->int s/get-schedule edn-response))

  (POST "/schedules/save" [_ :as request]
       (-> (:params request) (s/save-schedule! (uid request)) edn-response))

  (POST "/schedules/assoc" [_ :as request]
        (info "request is: " request)
        (-> (:edn-params request) (s/assoc-schedules! (uid request)) edn-response)))


;-----------------------------------------------------------------------
; Routes realted to workflows
;-----------------------------------------------------------------------
(defroutes workflow-routes
  (GET "/workflows" []
       (edn-response (w/ls-workflows)))

  (GET "/workflows/:id" [id]
       (-> id u/str->int w/get-workflow edn-response))

  (GET "/workflows/graph/:id" [id]
       (-> id u/str->int w/workflow-nodes edn-response))

  (GET "/workflows/trigger-now/:id" [id]
       (-> id u/str->int conductor/trigger-workflow-now edn-response))

  (POST "/workflows/save" [_ :as request]
       (info "workflow save: " (:params request))
       (-> (:params request) (w/save-workflow! (uid request)) edn-response)))

;-----------------------------------------------------------------------
; Routes realted to execution data
;-----------------------------------------------------------------------
(defroutes execution-routes
  (GET "/executions/:id" [id]
       (-> id u/str->int db/get-execution-details edn-response))

  (GET "/executions/workflows/:id" [id]
       (-> id u/str->int db/get-execution-workflow-details edn-response))

  (POST "/executions/search/q" [_ :as request]
        (info "executions search: " (:params request))
        (-> (:params request) search/executions edn-response))

  (GET "/executions/abort/:id" [id]
       (-> id u/str->int conductor/abort-execution edn-response))

  (GET "/executions/resume/:id/:vid" [id vid]
       (let [execution-id (u/str->int id)
             vertex-id (u/str->int vid)]
         (edn-response (conductor/resume-execution execution-id vertex-id)))))


;-----------------------------------------------------------------------
; Routes realted to nodes
;-----------------------------------------------------------------------
(defroutes node-routes
  (GET "/nodes" []
       (edn-response (db/ls-nodes)))

  (GET "/nodes/:id" [id]
       (-> id u/str->int db/get-node-by-id edn-response))

  (GET "/nodes/schedules/:id" [id]
       (-> id u/str->int db/schedule-ids-for-node edn-response)))



;-----------------------------------------------------------------------
; Collection of all routes.
;-----------------------------------------------------------------------
(def all-routes (cc/routes schedule-routes
                           node-routes
                           job-routes
                           workflow-routes
                           execution-routes
                           app-routes))
