(ns jsk.schedule
  (:require [taoensso.timbre :as log]
            [bouncer [core :as b] [validators :as v]]
            [jsk.db :as db]
            [korma.db :as k]
            [jsk.util :as ju]
            [jsk.quartz :as q])
  (:use [bouncer.validators :only [defvalidator]]
        [swiss arrows]))


;-----------------------------------------------------------------------
; Schedule lookups
;-----------------------------------------------------------------------
(defn ls-schedules
  []
  "Lists all schedules"
  (db/ls-schedules))

(defn get-schedule
  "Gets a schedule for the id specified"
  [id]
  (db/get-schedule id))

(defn get-schedules [ids]
  (db/get-schedule ids))

(defn get-schedule-by-name
  "Gets a schedule by name if one exists otherwise returns nil"
  [nm]
  (db/get-schedule-by-name nm))


(defn schedule-name-exists?
  "Answers true if schedule name exists"
  [nm]
  (-> nm db/get-schedule-by-name nil? not))

(def new-schedule-name? (complement schedule-name-exists?))

;-----------------------------------------------------------------------
; Validates if the s-name can be used
;-----------------------------------------------------------------------
(defn unique-name?  [id sname]
  (if-let [s (db/get-schedule-by-name sname)]
    (= id (:schedule-id s))
    true))

; NB the first is used to see if bouncer generated any errors
; bouncer returns a vector where the first item is a map of errors
(defn validate-save [{:keys [schedule-id] :as s}]
  (-> s
    (b/validate
       :schedule-name [v/required [(partial unique-name? schedule-id) :message "Schedule name must be unique."]]
       :cron-expression [v/required [q/cron-expr? :message "Invalid cron expression."]])
    first))


(defn- update-schedule-quartz! [schedule-id]
  (doseq [{:keys[node-schedule-id node-id node-type-id cron-expression]} (db/nodes-for-schedule schedule-id)]
    (q/update-trigger! node-schedule-id node-id node-type-id cron-expression)))

(defn- update-schedule! [{:keys [schedule-id] :as s} user-id]
  (db/update-schedule! s user-id)
  (update-schedule-quartz! schedule-id))


(defn- save-schedule* [{:keys [schedule-id] :as s} user-id]
  (-<>  (if (db/id? schedule-id)
          (update-schedule! s user-id)
          (db/insert-schedule! s user-id))
        {:success? true :schedule-id <>}))


;-----------------------------------------------------------------------
; Saves the schedule either inserting or updating depending on the
; schedule-id. If it is negative insert otherwise update.
;-----------------------------------------------------------------------
(defn save-schedule! [s user-id]
  (if-let [errors (validate-save s)]
    (ju/make-error-response errors)
    (save-schedule* s user-id)))

(defn- create-triggers [node-id]
  (log/info "Creating triggers for node " node-id)

  (let [{:keys[node-type-id]} (db/get-node-by-id node-id)
        ss-infos (db/get-node-schedule-info node-id)]
    (doseq [{:keys[node-schedule-id cron-expression]} ss-infos]
      (q/schedule-cron-job! node-schedule-id node-id node-type-id cron-expression))))

;-----------------------------------------------------------------------
; Associates a job to a set of schedule-ids.
; schedule-ids is a set of integer ids
;-----------------------------------------------------------------------
(defn assoc-schedules!
  ([{:keys [node-id schedule-ids]} user-id]
    (assoc-schedules! node-id schedule-ids user-id))

  ([node-id schedule-ids user-id]
     (let [node-schedule-ids (db/node-schedules-for-node node-id)]
       (log/info "user-id " user-id " requests job-id " node-id " be associated with schedules " schedule-ids)

       (k/transaction
         (db/rm-node-schedules! node-schedule-ids)
         (db/assoc-schedules! node-id schedule-ids user-id))

       (q/rm-triggers! node-schedule-ids)
       (create-triggers node-id)          ; add new schedules if any

       (log/info "job schedule associations made for job-id: " node-id)
       true)))
