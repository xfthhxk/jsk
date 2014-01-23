(ns jsk.common.job
  (:require [taoensso.timbre :as log]
            [bouncer [core :as b] [validators :as v]]
            [clojure.core.async :refer [put!]]
            [jsk.common.util :as util]
            [jsk.common.data :as data]
            [jsk.common.db :as db]
            [korma.db :as k]))

(def ^:private out-chan (atom nil))

(defn init
  "Sets the channel to use when updates are made to jobs."
  [ch]
  (reset! out-chan ch))


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

;-----------------------------------------------------------------------
; Saves the job either inserting or updating depending on the
; job-id.  If it is negative insert otherwise update.
;-----------------------------------------------------------------------
(defn save-job! [j user-id]
  (if-let [errors (validate-save j)]
    (util/make-error-response errors)
    (let [job-id (db/save-job j user-id)]
      (put! @out-chan {:msg :node-save :node-id job-id :node-type-id data/job-type-id})
      {:success? true :job-id job-id})))


(defn trigger-now
  "Puts a message on the conductor channel to trigger the job now."
  [job-id]
  (put! @out-chan {:msg :trigger-node :node-id job-id}))
