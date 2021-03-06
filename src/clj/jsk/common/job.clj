(ns jsk.common.job
  (:require [taoensso.timbre :as log]
            [bouncer [core :as b] [validators :as v]]
            [clojure.core.async :refer [put!]]
            [jsk.common.util :as util]
            [jsk.common.data :as data]
            [jsk.common.db :as db]
            [korma.db :as k]))

(defonce ^:private out-chan (atom nil))
(defonce ^:private ui-chan (atom nil))

(defn init
  "Sets the channel to use when updates are made to jobs."
  [ch ui-ch]
  (reset! out-chan ch)
  (reset! ui-chan ui-ch))


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

(defn- notify [{:keys [job-id job-name node-directory-id is-enabled]}]
  (let [schedule-count (-> job-id db/schedules-for-node count)
        info-msg {:msg :node-save
                  :node-id job-id
                  :node-type-id data/job-type-id}
        event-msg {:crud-event :node-save
                   :node-id job-id
                   :node-type-id data/job-type-id
                   :node-name job-name
                   :enabled? is-enabled
                   :scheduled? (> schedule-count 0)
                   :node-directory-id node-directory-id}]
      (put! @out-chan info-msg)
      (put! @ui-chan event-msg)))

;-----------------------------------------------------------------------
; Saves the job either inserting or updating depending on the
; job-id.  If it is negative insert otherwise update.
;-----------------------------------------------------------------------
(defn save-job! [{:keys [job-name node-directory-id] :as j} user-id]
  (if-let [errors (validate-save j)]
    (util/make-error-response errors)
    (let [job-id (db/save-job j user-id)]
      (notify (merge j {:job-id job-id}))
      {:success? true :job-id job-id})))

(defn update-enabled-status
  "Updates the is-enabled status for the job to be enabled?"
  [job-id enabled? user-id]
  (db/set-node-enabled job-id enabled?)
  (let [j (get-job job-id)]
    (notify j)))

(defn new-empty-job!
  "Makes a new job with default values for everything.
   Answers with the newly created job-id.
   Used by the explorer style ui."
  [dir-id agent-id user-id]
  (save-job! {:job-id -1
              :job-name (str "Job " (util/now-ms))
              :job-desc ""
              :node-directory-id dir-id
              :is-enabled true
              :execution-directory ""
              :command-line ""
              :max-concurrent 1
              :max-retries 1
              :agent-id agent-id}
             user-id))


(defn trigger-now
  "Puts a message on the conductor channel to trigger the job now."
  [job-id]
  (put! @out-chan {:msg :trigger-node :node-id job-id}))
