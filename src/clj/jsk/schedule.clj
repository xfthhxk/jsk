(ns jsk.schedule
  (:require [taoensso.timbre :as timbre :refer (info warn error)]
            [bouncer [core :as b] [validators :as v]]
            [jsk.db :as jdb]
            [jsk.util :as ju]
            [jsk.quartz :as q])
  (:use [korma core]
        [bouncer.validators :only [defvalidator]]
        [swiss-arrows core]))

(defentity schedule
  (pk :schedule-id)
  (entity-fields :schedule-id :schedule-name :schedule-desc :cron-expression))

;-----------------------------------------------------------------------
; Schedule lookups
;-----------------------------------------------------------------------
(defn ls-schedules
  []
  "Lists all schedules"
  (select schedule))

(defn get-schedule
  "Gets a schedule for the id specified"
  [id]
  (first (select schedule
          (where {:schedule-id id}))))

(defn get-schedule-by-name
  "Gets a schedule by name if one exists otherwise returns nil"
  [nm]
  (first (select schedule (where {:schedule-name nm}))))

(defn schedule-name-exists?
  "Answers true if schedule name exists"
  [nm]
  (-> nm get-schedule-by-name count zero? not))

(def new-schedule-name? (complement schedule-name-exists?))


;-----------------------------------------------------------------------
; Validates if the s-name can be used
;-----------------------------------------------------------------------
(defn unique-name?  [id sname]
  (let [s (get-schedule-by-name sname)]
    (or (nil? s)
        (-> :schedule-id s (= id)))))


(defn validate-save [{:keys [schedule-id] :as s}]
  (-> s
    (b/validate
       :schedule-name [v/required [(partial unique-name? schedule-id) :message "Schedule name must be unique."]]
       :cron-expression [v/required [q/cron-expr? :message "Invalid cron expression."]])
    first))

;-----------------------------------------------------------------------
; Insert a schedule
;-----------------------------------------------------------------------
(defn- insert-schedule! [m]
  (let [now (jdb/now)
        merged-map (merge m {:created-at now
                             :updated-at  now
                             :created-by "amar"
                             :updated-by "amar"})]
    (info "Creating new schedule: " merged-map)
    (-> (insert schedule (values merged-map))
         jdb/extract-identity)))

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

(defn- save-schedule* [{:keys [schedule-id] :as s}]
  (-<>  (if (neg? schedule-id)
          (insert-schedule! (dissoc s :schedule-id))
          (update-schedule! s))
        {:success? true :schedule-id <>}))


(defn- save-with-validation [handler]
  (fn [s]
    (let [errors (validate-save s)]
      (if errors
        (ju/make-error-response errors)
        (handler s)))))

;-----------------------------------------------------------------------
; Saves the schedule either inserting or updating depending on the
; schedule-id. If it is negative insert otherwise update.
;-----------------------------------------------------------------------
(def save-schedule! (save-with-validation save-schedule*))
