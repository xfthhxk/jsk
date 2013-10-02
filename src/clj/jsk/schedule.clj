(ns jsk.schedule
  (:require [taoensso.timbre :as timbre :refer (info warn error)]
            [jsk.db :as db])
  (:use [korma core]))

(defentity schedule
  (pk :schedule-id)
  (entity-fields :schedule-id :schedule-name :schedule-desc :cron-expression))

;-----------------------------------------------------------------------
; Enumerates all schedules
;-----------------------------------------------------------------------
(defn ls-schedules
  []
  "Lists all schedules"
  (select schedule))

;-----------------------------------------------------------------------
; Look up a schedule by id
;-----------------------------------------------------------------------
(defn get-schedule
  "Gets a schedule for the id specified"
  [id]
  (select schedule
          (where {:schedule-id id})))

;-----------------------------------------------------------------------
; Insert a schedule
;-----------------------------------------------------------------------
(defn- insert-schedule! [m]
  (let [merged-map (merge m {:created-at (:updated-at m)
                             :created-by (:updated-by m)})]
    (info "Creating new schedule: " merged-map)
    (-> (insert schedule (values merged-map))
         db/extract-identity)))

;-----------------------------------------------------------------------
; Update an existing schedule
; Answers with the schedule-id if update is successful.
;-----------------------------------------------------------------------
(defn- update-schedule! [{:keys [schedule-id] :as m}]
  (info "Updating schedule: " m)
  (update schedule
          (set-fields (dissoc m :schedule-id))
          (where {:schedule-id schedule-id}))
  schedule-id)


;-----------------------------------------------------------------------
; Saves the schedule either inserting or updating depending on the
; schedule-id. If it is negative insert otherwise update.
;-----------------------------------------------------------------------
(defn save-schedule!
  ([{:keys [schedule-id schedule-name schedule-desc cron-expression]}]
   (save-schedule! schedule-id schedule-name schedule-desc cron-expression "amar"))

  ([id nm desc cron-expr user-id]
    (let [m {:schedule-name nm
             :schedule-desc desc
             :cron-expression cron-expr
             :updated-by user-id
             :updated-at (db/now)}]
      (if (neg? id)
        (insert-schedule! m)
        (update-schedule! (assoc m :schedule-id id))))))
