(ns jsk.job
  (:require [taoensso.timbre :as timbre :refer (info warn error)]
            [bouncer [core :as b] [validators :as v]]
            [clojure.stacktrace :as st]
            [jsk.quartz :as q]
            [jsk.schedule :as s]
            [jsk.util :as ju]
            [jsk.db :as db]
            [korma.db :as k])
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
  (db/schedules-for-node job-id))


(defn- get-job-schedule-info [job-id]
  (db/get-node-schedule-info job-id))


(defn- create-triggers [job-id]
  (info "Creating triggers for job " job-id)
  (let [schedules (get-job-schedule-info job-id)]
    (q/schedule-cron-job! job-id ju/job-type-id schedules)))

;-----------------------------------------------------------------------
; Associates a job to a set of schedule-ids.
; schedule-ids is a set of integer ids
;-----------------------------------------------------------------------
(defn assoc-schedules!
  ([{:keys [job-id schedule-ids]} user-id]
    (assoc-schedules! job-id schedule-ids user-id))

  ([job-id schedule-ids user-id]
     (let [node-schedule-ids (db/node-schedules-for-node job-id)]
       (info "user-id " user-id " requests job-id " job-id " be associated with schedules " schedule-ids)

       (k/transaction
         (db/rm-node-schedules! node-schedule-ids)
         (db/assoc-schedules! job-id schedule-ids user-id))

       (q/rm-triggers! node-schedule-ids)
       (create-triggers job-id)          ; add new schedules if any

       (info "job schedule associations made for job-id: " job-id)
       true)))
