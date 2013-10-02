(ns jsk.job
  (:require [taoensso.timbre :as timbre :refer (info warn error)]
            [jsk.db :as db])
  (:use [korma core]))

(defentity job
  (pk :job-id)
  (entity-fields :job-id :job-name :job-desc :job-execution-directory :job-command-line :is-enabled))


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
