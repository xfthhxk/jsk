(ns jsk.execution
  (:require [taoensso.timbre :as timbre :refer (info warn error)]
            [jsk.quartz :as q]
            [jsk.db :as db]
            [clojure.core.async :refer [put!]])
  (:import (org.quartz CronExpression JobDetail JobExecutionContext
                       JobKey Scheduler Trigger TriggerBuilder TriggerKey))
  (:use [swiss-arrows core]))

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
            {:keys [execution-id] :as exec-info} (db/job-started job-id)]

        (info "Job id " job-id "is to be executed with execution-id " execution-id)

        (.put job-ctx :jsk-job-execution-info exec-info) ; stow for reading later
        (put! job-event-channel exec-info)))

    ;----------------------------------------------
    ; Record job finished in job_execution table.
    ;----------------------------------------------
    (jobWasExecuted [this job-ctx job-exception]
      (let [finish-ts (db/now)
            {:keys[execution-id] :as exec-info} (.get job-ctx :jsk-job-execution-info)
            success? (nil? job-exception)
            error-msg (exception->msg job-exception)
            merged-info (merge exec-info {:event :finish :finish-ts finish-ts :success? success? :error error-msg})]

        (db/job-finished execution-id success? finish-ts)
        (info "Job execution with id " execution-id " has finished. Success? " success?)

        (if error-msg
          (info "Job execution-id " execution-id " exception: " error-msg))

        (put! job-event-channel merged-info)))

    ;----------------------------------------------
    ; Nothing should be vetoing. Just log for now.
    ;----------------------------------------------
    (jobExecutionVetoed [this job-ctx]
      (warn "WTF? This job was vetoed: job-id=" (job-ctx->job-id job-ctx)))))




