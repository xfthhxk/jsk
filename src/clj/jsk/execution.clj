(ns jsk.execution
  "Handles job execution related things such as recording job starts and finishes."
  (:require [taoensso.timbre :as timbre :refer (info warn error)]
            [jsk.db :as jdb]
            [clojure.core.async :refer [put!]])
  (:import (org.quartz CronExpression JobDetail JobExecutionContext
                       JobKey Scheduler Trigger TriggerBuilder TriggerKey))
  (:use [korma core db]))

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

(defn job-started
  "Returns a map with keys: :event, :execution-id and :ts"
  [job-id]
  (let [ts (jdb/now)
        execution-id (job-started* job-id ts)]
    {:event :start :execution-id execution-id :ts ts}))



(defn job-finished [job-execution-id success?]
  "Marks the job as finished and sets the status."
  (let [status (if success? finished-success finished-error)
        ts (jdb/now)]

    ; update the data for the row
    (update job-execution (set-fields {:execution-status-id status :finished-at ts})
            (where {:job-execution-id job-execution-id}))

    {:event :finish :execution-id job-execution-id :ts ts :success? success?}))



(defn- job-ctx->job-id [^JobExecutionContext ctx]
  (-> ctx .getJobDetail .getKey .getName Integer/parseInt))


(defn make-job-recorder [listener-name job-event-channel]
  (reify

    org.quartz.JobListener

    (getName [this] listener-name)

    ; record job started in job_start table
    ; also stows the job execution id to later update when the job finishes
    (jobToBeExecuted [this job-ctx]
      (let [job-id (job-ctx->job-id job-ctx)
            {:keys [execution-id] :as exec-map} (job-started job-id)]

        (info "Job id " job-id "is to be executed with execution-id " execution-id)

        (.put job-ctx :jsk-job-execution-id execution-id) ; stow for reading later
        (put! job-event-channel exec-map)))

    ; marks the job execution as finished
    (jobWasExecuted [this job-ctx job-exception]
      (let [exec-id (.get job-ctx :jsk-job-execution-id)
            exec-map (job-finished exec-id (nil? job-exception))]
        (info "Job execution with id " exec-id " has finished.")
        (put! job-event-channel exec-map)))

    (jobExecutionVetoed [this job-ctx]
      (warn "WTF? This job was vetoed: job-id=" (job-ctx->job-id job-ctx)))))
