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

;-----------------------------------------------------------------------
; Default app routes.
;-----------------------------------------------------------------------
(defroutes app-routes
  (GET "/logout" req
    (friend/logout* (rr/redirect (str (:context req) "/"))))
  (route/resources "/")
  (route/not-found "Not found."))

;-----------------------------------------------------------------------
; Routes realted to jobs and job schedule associations.
;-----------------------------------------------------------------------
(defroutes job-routes
  (GET "/jobs" [_ :as request]
       (info (:session request))

       (response/edn (j/ls-jobs)))

  (GET "/jobs/:id" [id]
       (-> id j/get-job response/edn))

  (GET "/jobs/:id/sched-assoc" [id]
       (-> id j/schedules-for-job response/edn))

  (POST "/jobs/save" [_ :as request]
        (-> (:params request) j/save-job! response/edn))

  (POST "/jobs/assoc" [_ :as request]
        (info "request is: " request)
        (-> (:edn-params request) j/assoc-schedules! response/edn))

  (DELETE "/jobs/dissoc" [_ :as request]
        (-> (:params request) j/dissoc-schedules! response/edn)))

;-----------------------------------------------------------------------
; Routes realted to schedules
;-----------------------------------------------------------------------
(defroutes schedule-routes
  (GET "/schedules" []
       (response/edn (s/ls-schedules)))

  (GET "/schedules/:id" [id]
       (-> id s/get-schedule response/edn))

  (POST "/schedules/save" [_ :as request]
       (-> (:params request) s/save-schedule! response/edn)))



;-----------------------------------------------------------------------
; Collection of all routes.
;-----------------------------------------------------------------------
(def all-routes (cc/routes schedule-routes job-routes app-routes))
