(ns jsk.job
  (:require [taoensso.timbre :as timbre :refer (info warn error)]
            [bouncer [core :as b] [validators :as v]]
            [jsk.util :as ju]
            [jsk.db :as jdb])
  (:use [korma core db]
        [swiss-arrows core]))

(defentity job
  (pk :job-id)
  (entity-fields :job-id :job-name :job-desc :job-execution-directory :job-command-line :is-enabled))

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

(defn get-job
  "Gets a job for the id specified"
  [id]
  (first (select job
           (where {:job-id id}))))

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
  (let [merged-map (merge m {:create-user-id user-id :update-user-id user-id})]
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


(defn save-job* [{:keys [job-id] :as j} user-id]
  (-<> (if job-id
         (update-job! j user-id)
         (insert-job! j user-id))
       {:success? true :schedule-id <>}))

;-----------------------------------------------------------------------
; Saves the job either inserting or updating depending on the
; job-id.  If it is negative insert otherwise update.
;-----------------------------------------------------------------------
(defn save-job! [j user-id]
  (if-let [errors (validate-save j)]
    (ju/make-error-response errors)
    (save-job* j user-id)))


;-----------------------------------------------------------------------
; Deletes from job-schedule all rows matching job-schedule-ids
;-----------------------------------------------------------------------
(defn rm-job-schedules! [job-schedule-ids]
  (info "Removing job-schedule associations for the following PK: " job-schedule-ids)
  (delete job-schedule
    (where {:job-schedule-id [in job-schedule-ids]})))

;-----------------------------------------------------------------------
; Deletes from job-schedule all rows matching job-id
;-----------------------------------------------------------------------
(defn rm-schedules-for-job! [job-id]
  (info "Removing job-schedule associations for job id: " job-id)
  (delete job-schedule
    (where {:job-id job-id})))


;-----------------------------------------------------------------------
; Associates a job to a set of schedule-ids.
; schedule-ids is a set of integer ids
;-----------------------------------------------------------------------
(defn assoc-schedules!
  ([{:keys [job-id schedule-ids]}]
    (assoc-schedules! job-id schedule-ids "amar"))

  ([job-id schedule-ids user-id]
    (info "omg! job-id " job-id " is to be associated with schedules " schedule-ids)
    (let [ts (jdb/now)
          data {:job-id job-id
                :created-at ts
                :updated-at ts
                :created-by user-id
                :updated-by user-id}
          insert-maps (map #(assoc %1 :schedule-id %2) (repeat data) (set schedule-ids))]

      (transaction
        (rm-schedules-for-job! job-id) ; delete all existing entries for the job
        (insert job-schedule (values insert-maps)))

      (info "job schedules associations made for job-id: " job-id)
      true)))

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
; Schedule ids associated with the specified job id.
;-----------------------------------------------------------------------
(defn schedules-for-job [job-id]
  (->> (select job-schedule (where {:job-id job-id}))
       (map :schedule-id)
       set))

















