(ns jsk.routes
  (:require [compojure.core :refer [defroutes DELETE GET PUT POST]]
            [compojure.core :as cc]
            [compojure.route :as route]
            [noir.response :as response]
            [ring.util.response :as rr]
            [cemerick.friend :as friend]
            [taoensso.timbre :as timbre :refer (info warn error)]
            [jsk.job :as j]
            [jsk.schedule :as s]))

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
  (route/resources "public")
  (route/not-found "Not found."))

;-----------------------------------------------------------------------
; Routes realted to jobs and job schedule associations.
;-----------------------------------------------------------------------
(defroutes job-routes
  (GET "/jobs" [_ :as request]
       (response/edn (j/ls-jobs)))

  (GET "/jobs/:id" [id]
       (-> id j/get-job response/edn))

  (GET "/jobs/:id/sched-assoc" [id]
       (-> id j/schedules-for-job response/edn))

  (POST "/jobs/save" [_ :as request]
        (-> (:params request) (j/save-job! (uid request)) response/edn))

  (POST "/jobs/assoc" [_ :as request]
        (info "request is: " request)
        (-> (:edn-params request) (j/assoc-schedules! (uid request)) response/edn))

  (DELETE "/jobs/dissoc" [_ :as request]
        (-> (:params request) (j/dissoc-schedules! (uid request)) response/edn)))

;-----------------------------------------------------------------------
; Routes realted to schedules
;-----------------------------------------------------------------------
(defroutes schedule-routes
  (GET "/schedules" []
       (response/edn (s/ls-schedules)))

  (GET "/schedules/:id" [id]
       (-> id s/get-schedule response/edn))

  (POST "/schedules/save" [_ :as request]
       (-> (:params request) (s/save-schedule! (uid request)) response/edn)))



;-----------------------------------------------------------------------
; Collection of all routes.
;-----------------------------------------------------------------------
(def all-routes (cc/routes schedule-routes job-routes app-routes))