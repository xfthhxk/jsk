(ns jsk.job
  (:require [taoensso.timbre :as timbre :refer (info warn error)]
            [jsk.db :as db])
  (:use [korma core]))

(defentity job
  (pk :job-id)
  (entity-fields :job-id :job-name :job-desc :job-execution-directory :job-command-line :is-enabled))

(defentity job-schedule
  (pk :job-schedule-id)
  (entity-fields :job-schedule-id :job-id :schedule-id))


;-----------------------------------------------------------------------
; Enumerates all jobs
;-----------------------------------------------------------------------
(defn ls-jobs
  []
  "Lists all jobs"
  (info "Listing all jobs.")
  (select job))


;-----------------------------------------------------------------------
; Look up a job by id
;-----------------------------------------------------------------------
(defn get-job
  "Gets a job for the id specified"
  [id]
  (select job
          (where {:job-id id})))

;-----------------------------------------------------------------------
; Insert a job
;-----------------------------------------------------------------------
(defn- insert-job! [m]
  (let [merged-map (merge m {:created-at (:updated-at m)
                             :created-by (:updated-by m)})]
    (info "Creating new job with values: " merged-map)
    (-> (insert job (values merged-map))
        db/extract-identity)))


;-----------------------------------------------------------------------
; Update an existing schedule.
; Answers with the job-id if update is successful.
;-----------------------------------------------------------------------
(defn- update-job! [{:keys [job-id] :as m}]
  (info "Updating job: " m)
  (update job
          (set-fields (dissoc m :job-id))
          (where {:job-id job-id}))
  job-id)


;-----------------------------------------------------------------------
; Saves the job either inserting or updating depending on the
; job-id.  If it is negative insert otherwise update.
;-----------------------------------------------------------------------
(defn save-job!
  ([{:keys [job-id job-name job-desc job-execution-directory job-command-line is-enabled]}]
   (save-job! job-id job-name job-desc job-execution-directory job-command-line is-enabled "amar"))

  ([id nm desc exec-dir cmd-line enabled? user-id]
   (info "Hi we're saving a job")
   (let [m {:job-name nm
            :job-desc desc
            :job-execution-directory exec-dir
            :job-command-line cmd-line
            :is-enabled enabled?
            :updated-by user-id
            :updated-at (db/now)}]
     (if (neg? id)
       (insert-job! m)
       (update-job! (assoc m :job-id id))))))


;-----------------------------------------------------------------------
; Associates a job to a set of schedule-ids.
; schedule-ids is a set of integer ids
;-----------------------------------------------------------------------
(defn assoc-schedules!
  ([{:keys [job-id schedule-ids]}]
    (assoc-schedules! job-id schedule-ids))

  ([job-id schedule-ids user-id]
    (info "job-id " job-id " is to be associated with schedules " schedule-ids)
    (let [ts (db/now)
          ins-map-fn #({:job-id job-id
                        :schedule-id %1
                        :created-at ts
                        :updated-at ts
                        :created-by user-id
                        :updated-by user-id})
          insert-maps (map ins-map-fn schedule-ids)]
       (insert job-schedule (values insert-maps)))))

;-----------------------------------------------------------------------
; Disassociates schedule-ids from a job.
; schedule-ids is a set of integer ids
;-----------------------------------------------------------------------
(defn dissoc-schedules!
  ([{:keys [job-id schedule-ids]}]
   (dissoc-schedules! job-id schedule-ids))

  ([job-id schedule-ids]
    (info "job-id " job-id " is to be disassociated from schedules " schedule-ids)
    (delete job-schedule
            (where {:job-id job-id
                    :schedule-id [in schedule-ids]}))))


;-----------------------------------------------------------------------
; Deletes from job-schedule all rows matching job-schedule-ids
;-----------------------------------------------------------------------
(defn rm-job-schedules! [job-schedule-ids]
  (info "Removing job-schedule associations for the following PK: " job-schedule-ids)
  (delete job-schedule
          (where {:job-schedule-id [in job-schedule-ids]})))




















