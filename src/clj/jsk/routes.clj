(ns jsk.routes
  (:require [compojure.core :refer [defroutes DELETE GET PUT POST]]
            [compojure.core :as cc]
            [compojure.route :as route]
            [ring.util.response :as rr]
            [cemerick.friend :as friend]
            [taoensso.timbre :as timbre :refer (info warn error)]
            [jsk.job :as j]
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

(defn- uid [request] 1)
  ;(-> request current-user :app-user-id))

;-----------------------------------------------------------------------
; Default app routes.
;-----------------------------------------------------------------------
(defroutes app-routes
  (GET "/logout" req
    (friend/logout* (rr/redirect (str (:context req) "/"))))
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
       (-> id j/get-job edn-response))

  (GET "/jobs/:id/sched-assoc" [id]
       (-> id j/schedules-for-job edn-response))

  (POST "/jobs/save" [_ :as request]
        (-> (:params request) (j/save-job! (uid request)) edn-response))

  (POST "/jobs/assoc" [_ :as request]
        (info "request is: " request)
        (-> (:edn-params request) (j/assoc-schedules! (uid request)) edn-response))

  (DELETE "/jobs/dissoc" [_ :as request]
        (-> (:params request) (j/dissoc-schedules! (uid request)) edn-response)))

;-----------------------------------------------------------------------
; Routes realted to schedules
;-----------------------------------------------------------------------
(defroutes schedule-routes
  (GET "/schedules" []
       (edn-response (s/ls-schedules)))

  (GET "/schedules/:id" [id]
       (-> id s/get-schedule edn-response))

  (POST "/schedules/save" [_ :as request]
        (info "booy")
       (-> (:params request) (s/save-schedule! (uid request)) edn-response)))



;-----------------------------------------------------------------------
; Collection of all routes.
;-----------------------------------------------------------------------
(def all-routes (cc/routes schedule-routes job-routes app-routes))
