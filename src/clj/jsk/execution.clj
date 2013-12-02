(ns jsk.execution
  (:require [taoensso.timbre :as timbre :refer (debug info warn error)]
            [jsk.quartz :as q]
            [jsk.db :as db]
            [clojurewerkz.quartzite.conversion :as qc]
            [clojure.core.async :refer [put!]])
  (:import (org.quartz CronExpression JobDetail JobExecutionContext
                       JobKey Scheduler Trigger TriggerBuilder TriggerKey))
  (:use [swiss-arrows core]))

(def info-channel (atom nil))
(def conductor-channel (atom nil))

(defn register-event-channels! [info-ch conductor-ch]
  (reset! info-channel info-ch)
  (reset! conductor-channel conductor-ch))

(defn- job-ctx->job-id [^JobExecutionContext ctx]
  (-> ctx .getJobDetail .getKey .getName Integer/parseInt))

(defn- job-ctx->jsk-data [^JobExecutionContext ctx]
  (-> ctx qc/from-job-data))

(defn- exception->msg [^Throwable t]
  (when t
    (.getMessage t)))

;----------------------------------------------
; Record job about to start
;
; look for :exec-vertex-id in JobDataMap that is the
; db row to update for marking job started
; Also notify conductor of job starting via
; conductor channel
;
;----------------------------------------------
(defn- record-pre-job-execution [job-ctx _]
  (let [{:strs[execution-id node-id exec-wf-id trigger-src
               exec-vertex-id start-ts node-nm]} (job-ctx->jsk-data job-ctx)
        msg {:event :job-started
             :exec-wf-id exec-wf-id
             :execution-id execution-id
             :node-id node-id
             :node-nm node-nm
             :exec-vertex-id exec-vertex-id
             :status db/started-status
             :start-ts start-ts}]

    (db/execution-vertex-started exec-vertex-id start-ts)

    (info "Job id " node-id "is to be executed with exec-vertex-id " exec-vertex-id)

    (put! @conductor-channel msg)
    (put! @info-channel msg)))

;----------------------------------------------
; Record job finished
;----------------------------------------------
(defn- record-post-job-execution
  "Records that a job has finished."
  [job-ctx job-exception]
  (let [{:strs[execution-id node-id exec-wf-id trigger-src
               exec-vertex-id start-ts node-nm]} (job-ctx->jsk-data job-ctx)
        ts (db/now)
        success? (nil? job-exception)
        status (if success? db/finished-success db/finished-error)
        error-msg (exception->msg job-exception)
        msg {:event :job-finished
             :execution-id execution-id
             :node-id node-id
             :node-nm node-nm
             :exec-wf-id exec-wf-id
             :exec-vertex-id exec-vertex-id
             :start-ts start-ts
             :finish-ts ts
             :success? success?
             :status status
             :error error-msg}]

    (info "exec-wf-id is " exec-wf-id)

     (db/execution-vertex-finished exec-vertex-id status ts)
     (info "Execution vertex id " exec-vertex-id " for job " node-id " has finished. Success? " success?)

     (if error-msg
       (info "Execution vertex id " exec-vertex-id " exception: " error-msg))

     (put! @conductor-channel msg)
     (put! @info-channel msg)))



;--------------------------------------------------------------
; Ignores jobs which specify the "ignore-execution?" property
;--------------------------------------------------------------
(defn- with-exclude-ignored-jobs
  "Middleware for excluding jobs whose execution should be ignored."
  [handler]
  (fn [job-ctx job-exception]
    (if (-> job-ctx q/ignore-execution? not)
      (handler job-ctx job-exception))))

(def pre-job-execution (-> record-pre-job-execution
                           with-exclude-ignored-jobs))

(def post-job-execution (-> record-post-job-execution
                            with-exclude-ignored-jobs))


(defn make-job-recorder [listener-name]
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
      (pre-job-execution job-ctx nil))

    ;----------------------------------------------
    ; Record job finished in job_execution table.
    ;----------------------------------------------
    (jobWasExecuted [this job-ctx job-exception]
      (post-job-execution job-ctx job-exception))

    ;----------------------------------------------
    ; Nothing should be vetoing. Just log for now.
    ;----------------------------------------------
    (jobExecutionVetoed [this job-ctx]
      (warn "WTF? This job was vetoed: job-id=" (job-ctx->job-id job-ctx)))))




