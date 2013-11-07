(ns jsk.job
  (:require [taoensso.timbre :as timbre :refer (info warn error)]
            [bouncer [core :as b] [validators :as v]]
            [clojure.stacktrace :as st]
            [jsk.quartz :as q]
            [jsk.schedule :as s]
            [jsk.util :as ju]
            [jsk.db :as db]
            [clojure.core.async :refer [put!]])
  (:use [swiss-arrows core]))

;-----------------------------------------------------------------------
; Job lookups
;-----------------------------------------------------------------------
(defn ls-jobs
  "Lists all jobs"
  []
  (db/ls-jobs))

(defn enabled-jobs
  "Gets all active jobs."
  []
  (db/enabled-jobs))

(defn get-job
  "Gets a job for the id specified"
  [id]
  (db/get-job id))

(defn get-job-name
  "Answers with the job name for the job id, otherwise nil if no such job."
  [id]
  (db/get-job-name id))

(defn get-job-by-name
  "Gets a job by name if one exists otherwise returns nil"
  [nm]
  (db/get-job-by-name nm))

(defn job-name-exists?
  "Answers true if job name exists"
  [nm]
  (db/job-name-exists? nm))

;-----------------------------------------------------------------------
; Validates if the job name can be used
;-----------------------------------------------------------------------
(defn unique-name? [id jname]
  (if-let [j (get-job-by-name jname)]
    (= id (:job-id j))
    true))


; NB the first is used to see if bouncer generated any errors
; bouncer returns a vector where the first item is a map of errors
(defn validate-save [{:keys [job-id] :as j}]
  (-> j
      (b/validate
         :job-name [v/required [(partial unique-name? job-id) :message "Job name must be unique."]])
      first))

(defn- save-job*
  "Saves the job to the database and the scheduler."
  [j user-id]
  (let [job-id (db/save-job j user-id)]
    (q/save-job! (assoc j :job-id job-id))
    {:success? true :job-id job-id}))

;-----------------------------------------------------------------------
; Saves the job either inserting or updating depending on the
; job-id.  If it is negative insert otherwise update.
;-----------------------------------------------------------------------
(defn save-job! [j user-id]
  (if-let [errors (validate-save j)]
    (ju/make-error-response errors)
    (save-job* j user-id)))

;-----------------------------------------------------------------------
; Schedule ids associated with the specified job id.
;-----------------------------------------------------------------------
(defn schedules-for-job [job-id]
  (db/schedules-for-job job-id))


;-----------------------------------------------------------------------
; Deletes from job-schedule all rows matching job-schedule-ids
; Also removes from quartz all jobs matching the job-schedule-id
; ie the triggers IDd by job-scheduler-id
;-----------------------------------------------------------------------
(defn- rm-job-schedules! [job-schedule-ids]
  (info "Removing job-schedule associations for the following PK: " job-schedule-ids)
  (q/rm-triggers! job-schedule-ids)
  (db/rm-job-schedules! job-schedule-ids))

;-----------------------------------------------------------------------
; Deletes from quartz and job-schedule table.
;-----------------------------------------------------------------------
(defn rm-schedules-for-job! [job-id]
  (info "Removing job-schedule associations for job id: " job-id)
  (-> job-id db/job-schedules-for-job rm-job-schedules!))

(defn- get-job-schedule-info [job-id]
  (db/get-job-schedule-info job-id))


(defn- create-triggers [job-id]
  (info "Creating triggers for job " job-id)
  (let [schedules (get-job-schedule-info job-id)]
    (q/schedule-cron-job! job-id schedules)))


;-----------------------------------------------------------------------
; Associates a job to a set of schedule-ids.
; schedule-ids is a set of integer ids
;-----------------------------------------------------------------------
(defn assoc-schedules!
  ([{:keys [job-id schedule-ids]} user-id]
    (assoc-schedules! job-id schedule-ids user-id))

  ([job-id schedule-ids user-id]
     (info "user-id " user-id " requests job-id " job-id " be associated with schedules " schedule-ids)

     (db/assoc-schedules! job-id schedule-ids user-id)
     (create-triggers job-id)

     (info "job schedule associations made for job-id: " job-id)
     true))

;-----------------------------------------------------------------------
; Disassociates schedule-ids from a job.
; schedule-ids is a set of integer ids
;-----------------------------------------------------------------------
(defn dissoc-schedules!
  ([{:keys [job-id schedule-ids]} user-id]
   (dissoc-schedules! job-id schedule-ids user-id))

  ([job-id schedule-ids user-id]
    (info "user-id " user-id " requests job-id " job-id " be dissociated from schedules " schedule-ids)
    (db/dissoc-schedules! job-id schedule-ids user-id)
    (info "job schedule dissociations complete for job-id: " job-id)
    true))
