(ns jsk.quartz
  "JSK quartz"
  (:require [jsk.ps :as ps]
            [jsk.conf :as conf]
            [jsk.util :as ju]
            [clojure.core.async :refer [put!]]
            [taoensso.timbre :as log]
            [clojurewerkz.quartzite.scheduler :as qs]
            [clojurewerkz.quartzite.conversion :as qc]
            [clojurewerkz.quartzite.triggers :as t]
            [clojurewerkz.quartzite.jobs :as j]
            [clojurewerkz.quartzite.schedule.cron :as cron])
  (:import (org.quartz.impl.matchers GroupMatcher EverythingMatcher))
  (:import (org.quartz CronExpression JobDetail JobExecutionContext
                       JobKey Scheduler Trigger TriggerBuilder TriggerKey)))

;-----------------------------------------------------------------------
; Quartz interop section.
;-----------------------------------------------------------------------

(defn- add-job [^JobDetail job-detail]
  (.addJob ^Scheduler @qs/*scheduler* job-detail true))

(defn- schedule-trigger [^Trigger trigger]
  (.scheduleJob ^Scheduler @qs/*scheduler* trigger))

(defn- reschedule-job
  ([^Trigger new-trigger] (reschedule-job (.getKey new-trigger) new-trigger))
  ([^TriggerKey tkey ^Trigger new-trigger]
   (.rescheduleJob ^Scheduler @qs/*scheduler* tkey new-trigger)))

(defn- trigger-for-job-key
  "Answers with the Trigger for the given job-key."
  [^TriggerBuilder tb ^JobKey job-key]
  (.forJob tb job-key))

(defn make-job-key [id]
  (j/key (str id) "jsk-job"))

(defn make-trigger-job-key [id]
  (j/key (str id) "jsk-trigger-job"))

(defn make-trigger-key [id]
  (t/key (str id) "jsk-trigger"))

(defn ls-trigger-group-names []
  (qs/get-trigger-group-names))

(defn get-job-triggers [id]
  (.getTriggersOfJob ^Scheduler @qs/*scheduler* (make-trigger-job-key id)))

;-----------------------------------------------------------------------
; Answers if the cron expression is valid or not.
;-----------------------------------------------------------------------
(defn cron-expr? [expr]
  (if expr
    (CronExpression/isValidExpression expr)
    false))


;(defn register-job-execution-recorder! [job-execution-recorder]
;  (-> ^Scheduler @qs/*scheduler* .getListenerManager (.addJobListener job-execution-recorder (EverythingMatcher/allJobs))))

(def quartz-channel (atom nil))

(defn init [quartz-ch]
  (reset! quartz-channel quartz-ch)
  (qs/initialize))

(defn start []
  (qs/start))

(defn stop []
  (qs/shutdown))


;(defn ignore-execution?
;  "Answers if the execution should be ignored. Quartz triggers are used
;   to put msgs on the quartz-channel.  Quartz triggered jobs will
;   have this property set to true. Not a 'real' job being executed."
;  [^JobExecutionContext ctx]
;  (let [{:strs [ignore-execution?]} (qc/from-job-data ctx)]
;    (if ignore-execution?
;      true
;      false)))


;-----------------------------------------------------------------------
; Didn't know about NativeJob. Maybe use that instead.
;-----------------------------------------------------------------------
;(j/defjob ShellJob
;  [ctx]
;  (let [{:strs [cmd-line exec-dir exec-vertex-id execution-id timeout]} (qc/from-job-data ctx)
;        log-file-name (str (conf/exec-log-dir) "/" exec-vertex-id ".log")]
;
;    (log/info "cmd-line: " cmd-line ", exec-dir: " exec-dir ", log-file: " log-file-name)
;    (ps/exec1 execution-id exec-vertex-id timeout cmd-line exec-dir log-file-name)))



;-----------------------------------------------------------------------
; This is what actually gets registered with quartz.  This job
; puts a message on the conductor's queue which then actually
; triggers the job.
;-----------------------------------------------------------------------
(j/defjob JskTriggerJob
  [ctx]
  (let [{:strs [node-id]} (qc/from-job-data ctx)]
    (log/info "Quartz triggering job with id " node-id)
    (put! @quartz-channel {:event :trigger-node :node-id node-id})))


;-----------------------------------------------------------------------
; Creates a ShellJob instance by specifying JobData
; ie the arguments and the key
;
; Returns a job instance
;-----------------------------------------------------------------------
; -- may not be necessary anymore since agent handles the execution and we look up the
; -- actual job from the database when it comes time to execute the job

;(defn- make-shell-job
;  [job-id cmd-line exec-dir]
;    (let [job-map {"cmd-line" cmd-line "exec-dir" exec-dir} ; have to use string keys for quartz
;          job-key (make-job-key job-id)]
;
;      ; NB. j/build is a macro which creates and passes a job builder in using ->
;      (j/build (j/of-type ShellJob)
;               (j/using-job-data job-map)
;               (j/with-identity job-key)
;               (j/store-durably))))

; NB node-id has to be unique across jobs *and* workflows
(defn- make-triggerable-job [node-id]
  (j/build (j/of-type JskTriggerJob)
           (j/using-job-data {"node-id" node-id}) ; string keys for quartz
           (j/with-identity (make-trigger-job-key node-id))
           (j/store-durably)))




;-----------------------------------------------------------------------
; Adds or replaces a job within the scheduler.
;-----------------------------------------------------------------------
;(defn save-job! [{:keys [command-line job-id execution-directory]}]
;  (add-job (make-shell-job job-id command-line execution-directory)))


;-----------------------------------------------------------------------
; Makes a trigger from a Schedule instance.
; Trigger-id is a string.
; Returns a trigger instance.
;-----------------------------------------------------------------------
(defn- make-cron-trigger
  "Makes a cron trigger instance based on the schedule specified."
  [trigger-id cron-expr node-id]

  (log/info "make-cron-trigger id " trigger-id ", cron: " cron-expr ", node-id:" node-id)

  (let [cron-sched (cron/schedule (cron/cron-schedule cron-expr))
        trigger-key (make-trigger-key trigger-id)
        job-key (make-trigger-job-key node-id)]

    (add-job (make-triggerable-job node-id))

    (t/build
     (t/with-identity trigger-key)
     (trigger-for-job-key job-key)
     (t/start-now)
     (t/with-schedule cron-sched))))

(defn schedule-cron-job! [trigger-id node-id cron-expr]
  (log/info "Scheduling: trigger:" trigger-id ", node-id:" node-id ", cron:" cron-expr)
  (schedule-trigger (make-cron-trigger trigger-id cron-expr node-id)))

;-----------------------------------------------------------------------
; Deletes triggers specified by the trigger-ids.
;-----------------------------------------------------------------------
(defn rm-triggers! [trigger-ids]
  (log/info "Deleting triggers: " trigger-ids)
  (qs/delete-triggers (map make-trigger-key trigger-ids)))


;-----------------------------------------------------------------------
; Update triggers.
;-----------------------------------------------------------------------
(defn update-trigger! [node-schedule-id node-id cron-expression]
  (reschedule-job (make-cron-trigger node-schedule-id cron-expression node-id)))











