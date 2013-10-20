(ns jsk.job
  (:require [taoensso.timbre :as timbre :refer (info warn error)]
            [bouncer [core :as b] [validators :as v]]
            [clojure.stacktrace :as st]
            [jsk.quartz :as q]
            [jsk.schedule :as s]
            [jsk.util :as ju]
            [jsk.db :as jdb]
            [clojure.core.async :refer [put!]])
  (:import (org.quartz CronExpression JobDetail JobExecutionContext
                       JobKey Scheduler Trigger TriggerBuilder TriggerKey))
  (:use [korma core db]
        [swiss-arrows core]))


(defentity job
  (pk :job-id)
  (entity-fields :job-id :job-name :job-desc :execution-directory :command-line :is-enabled))
  ;(many-to-many s/schedule :job-schedule))

(defentity job-schedule
  (pk :job-schedule-id)
  (entity-fields :job-schedule-id :job-id :schedule-id))




;-----------------------------------------------------------------------
; Job lookups
;-----------------------------------------------------------------------
(defn ls-jobs
  "Lists all jobs"
  []
  (select job))

(defn enabled-jobs
  "Gets all active jobs."
  []
  (select job (where {:is-enabled true})))

(defn get-job
  "Gets a job for the id specified"
  [id]
  (first (select job
           (where {:job-id id}))))

(defn get-job-name
  "Answers with the job name for the job id, otherwise nil if no such job."
  [id]
  (if-let [j (get-job id)]
    (:job-name j)))

(defn get-job-by-name
  "Gets a job by name if one exists otherwise returns nil"
  [nm]
  (first (select job (where {:job-name nm}))))

(defn job-name-exists?
  "Answers true if job name exists"
  [nm]
  (-> nm get-job-by-name nil? not))


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

;-----------------------------------------------------------------------
; Insert a job. Answers with the inserted job's row id.
;-----------------------------------------------------------------------
(defn- insert-job! [m user-id]
  (let [merged-map (merge (dissoc m :job-id) {:create-user-id user-id :update-user-id user-id})]
    (info "Creating new job with values: " merged-map)
    (-> (insert job (values merged-map))
        jdb/extract-identity)))


;-----------------------------------------------------------------------
; Update an existing job.
; Answers with the job-id if update is successful.
;-----------------------------------------------------------------------
(defn- update-job! [{:keys [job-id] :as m} user-id]
  (let [merged-map (merge m {:update-user-id user-id :update-at (jdb/now)})]
    (info "Updating job: " m)
    (update job (set-fields (dissoc m :job-id))
      (where {:job-id job-id})))
  job-id)


(defn- save-job-db [{:keys [job-id] :as j} user-id]
  (if (jdb/id? job-id)
      (update-job! j user-id)
      (insert-job! j user-id)))

(defn- save-job*
  "Saves the job to the database and the scheduler."
  [j user-id]
  (let [job-id (save-job-db j user-id)]
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
  (->> (select job-schedule (where {:job-id job-id}))
       (map :schedule-id)
       set))

;-----------------------------------------------------------------------
; Job schedule ids associated with the specified job id.
;-----------------------------------------------------------------------
(defn- job-schedules-for-job [job-id]
  (->> (select job-schedule (where {:job-id job-id}))
       (map :job-schedule-id)
       set))


;-----------------------------------------------------------------------
; Deletes from job-schedule all rows matching job-schedule-ids
; Also removes from quartz all jobs matching the job-schedule-id
; ie the triggers IDd by job-scheduler-id
;-----------------------------------------------------------------------
(defn- rm-job-schedules! [job-schedule-ids]
  (info "Removing job-schedule associations for the following PK: " job-schedule-ids)
  (q/rm-triggers! job-schedule-ids)
  (delete job-schedule (where {:job-schedule-id [in job-schedule-ids]})))

;-----------------------------------------------------------------------
; Deletes from job-schedule all rows matching job-id
;-----------------------------------------------------------------------
(defn rm-schedules-for-job! [job-id]
  (info "Removing job-schedule associations for job id: " job-id)
  (-> job-id job-schedules-for-job rm-job-schedules!))

(defn- get-job-schedule-info [job-id]
  (exec-raw ["select js.job_schedule_id, s.* from job_schedule js join schedule s on js.schedule_id = s.schedule_id where job_id = ?" [job-id]] :results))


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

    (let [schedule-id-set (set schedule-ids)
          data {:job-id job-id :create-user-id user-id}
          insert-maps (map #(assoc %1 :schedule-id %2) (repeat data) schedule-id-set)]

      (transaction
        (rm-schedules-for-job! job-id) ; delete all existing entries for the job
        (if (not (empty? insert-maps))
          (insert job-schedule (values insert-maps))))

      (create-triggers job-id)

      (info "job schedule associations made for job-id: " job-id)
      true)))

;-----------------------------------------------------------------------
; Disassociates schedule-ids from a job.
; schedule-ids is a set of integer ids
;-----------------------------------------------------------------------
(defn dissoc-schedules!
  ([{:keys [job-id schedule-ids]} user-id]
   (dissoc-schedules! job-id schedule-ids user-id))

  ([job-id schedule-ids user-id]
    (info "user-id " user-id " requests job-id " job-id " be dissociated from schedules " schedule-ids)

    (delete job-schedule
      (where {:job-id job-id
              :schedule-id [in schedule-ids]}))
   (info "job schedule dissociations complete for job-id: " job-id)
   true))







;-----------------------------------------------------------------------
; Job execution stuff should be in another place likely
; schedule-ids is a set of integer ids
;-----------------------------------------------------------------------
; These are tied to what is in the job_execution_status table
(def started-status 1)
(def finished-success 2)
(def finished-error 3)

(defentity job-execution
  (pk :job-execution-id)
  (entity-fields :job-id :execution-status-id :started-at :finished-at))

(defn- job-started*
  "Creates a row in the job_execution table and returns the id
   of the newly created row."
  [job-id started-at]
  (-> (insert job-execution
        (values {:job-id job-id
                 :execution-status-id started-status
                 :started-at started-at}))
      jdb/extract-identity))


(defn- job-started
  "Returns a map with keys: :event, :execution-id and :ts"
  [job-id]
  (let [ts (jdb/now)
        execution-id (job-started* job-id ts)
        job-name (get-job-name job-id)]
    {:event :start
     :execution-id execution-id
     :start-ts ts
     :job-id job-id
     :job-name job-name}))



(defn- job-finished [job-execution-id success? finished-ts]
  "Marks the job as finished and sets the status."
  (let [status (if success? finished-success finished-error)]
    (update job-execution (set-fields {:execution-status-id status :finished-at finished-ts})
            (where {:job-execution-id job-execution-id}))))




(defn- job-ctx->job-id [^JobExecutionContext ctx]
  (-> ctx .getJobDetail .getKey .getName Integer/parseInt))


(defn- exception->msg [^Throwable t]
  (when t
    (.getMessage t)))
    ;(let [rc (st/root-cause t)]
    ;(format "%s: %s" (class rc) (.getMessage rc)))))

(defn make-job-recorder [listener-name job-event-channel]
  (reify

    org.quartz.JobListener

    ;----------------------------------------------
    ; Answers with this listener's name
    ;----------------------------------------------
    (getName [this] listener-name)

    ;----------------------------------------------
    ; Record job started in job_execution table.
    ; Also stows the job execution info for later
    ; when a job finishes.
    ;----------------------------------------------
    (jobToBeExecuted [this job-ctx]
      (let [job-id (job-ctx->job-id job-ctx)
            {:keys [execution-id] :as exec-info} (job-started job-id)]

        (info "Job id " job-id "is to be executed with execution-id " execution-id)

        (.put job-ctx :jsk-job-execution-info exec-info) ; stow for reading later
        (put! job-event-channel exec-info)))

    ;----------------------------------------------
    ; Record job finished in job_execution table.
    ;----------------------------------------------
    (jobWasExecuted [this job-ctx job-exception]
      (let [finish-ts (jdb/now)
            {:keys[execution-id] :as exec-info} (.get job-ctx :jsk-job-execution-info)
            success? (nil? job-exception)
            error-msg (exception->msg job-exception)
            merged-info (merge exec-info {:event :finish :finish-ts finish-ts :success? success? :error error-msg})]

        (job-finished execution-id success? finish-ts)
        (info "Job execution with id " execution-id " has finished. Success? " success?)

        (if error-msg
          (info "Job execution-id " execution-id " exception: " error-msg))

        (put! job-event-channel merged-info)))

    ;----------------------------------------------
    ; Nothing should be vetoing. Just log for now.
    ;----------------------------------------------
    (jobExecutionVetoed [this job-ctx]
      (warn "WTF? This job was vetoed: job-id=" (job-ctx->job-id job-ctx)))))








