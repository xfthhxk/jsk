(ns jsk.routes
  (:require [compojure.core :refer [defroutes DELETE GET PUT POST]]
            [compojure.route :as route]
            [noir.response :as response]
            [ring.util.response :as rr]
            [jsk.job :as j]
            [jsk.schedule :as s]))

;-----------------------------------------------------------------------
; Default app routes.
;-----------------------------------------------------------------------
(defroutes app-routes
  (route/resources "/")
  (route/not-found "Not found."))

;-----------------------------------------------------------------------
; Routes realted to jobs and job schedule associations.
;-----------------------------------------------------------------------
(defroutes job-routes
  (GET "/jobs" []
       (response/edn (j/ls-jobs)))

  (GET "/jobs/:id" [id]
       (-> id j/get-job response/edn))

  (POST "/jobs/save" [_ :as request]
        (-> (:params request) j/save-job! response/edn))

  (POST "/jobs/assoc" [_ :as request]
        (-> (:params request) j/assoc-schedules! response/edn))

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
(def all-routes [schedule-routes job-routes app-routes])
