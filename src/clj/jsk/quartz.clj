(ns jsk.quartz
  "JSK quartz"
  (:require [jsk.ps :as ps]
            [jsk.conf :as conf]
            [clojure.core.async :refer [put!]]
            [taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal spy with-log-level)]
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

;-----------------------------------------------------------------------
; Answers if the cron expression is valid or not.
;-----------------------------------------------------------------------
(defn cron-expr? [expr]
  (if expr
    (CronExpression/isValidExpression expr)
    false))


(defn register-job-execution-recorder! [job-execution-recorder]
  (-> ^Scheduler @qs/*scheduler* .getListenerManager (.addJobListener job-execution-recorder (EverythingMatcher/allJobs))))

(def conductor-channel (atom nil))

(defn init [conductor-ch]
  (reset! conductor-channel conductor-ch)
  (qs/initialize))

(defn start []
  (qs/start))

(defn stop []
  (qs/shutdown))


(defn ignore-execution?
  "Answers if the execution should be ignored. Quartz triggers are used
   to put msgs on the conductor-channel.  Quartz triggered jobs will
   have this property set to true. Not a 'real' job being executed."
  [^JobExecutionContext ctx]
  (let [{:strs [ignore-execution?]} (qc/from-job-data ctx)]
    (if ignore-execution?
      true
      false)))


;-----------------------------------------------------------------------
; Didn't know about NativeJob. Maybe use that instead.
;-----------------------------------------------------------------------
(j/defjob ShellJob
  [ctx]
  (let [{:strs [cmd-line exec-dir exec-vertex-id]} (qc/from-job-data ctx)
        log-file-name (str (conf/exec-log-dir) "/" exec-vertex-id ".log")]

    (info "cmd-line: " cmd-line ", exec-dir: " exec-dir ", log-file: " log-file-name)
    (ps/exec cmd-line exec-dir log-file-name)))


;-----------------------------------------------------------------------
; This is what actually gets registered with quartz.  This job
; puts a message on the conductor's queue which then actually
; triggers the job.
;-----------------------------------------------------------------------
(j/defjob JskTriggerJob
  [ctx]
  (let [{:strs [job-id]} (qc/from-job-data ctx)]
    (put! @conductor-channel {:event :trigger-job :job-id job-id :trigger-src :quartz})))


;-----------------------------------------------------------------------
; Creates a ShellJob instance by specifying JobData
; ie the arguments and the key
;
; Returns a job instance
;-----------------------------------------------------------------------
(defn- make-shell-job
  [job-id cmd-line exec-dir]
    (let [job-map {"cmd-line" cmd-line "exec-dir" exec-dir} ; have to use string keys for quartz
          job-key (make-job-key job-id)]

      ; NB. j/build is a macro which creates and passes a job builder in using ->
      (j/build (j/of-type ShellJob)
               (j/using-job-data job-map)
               (j/with-identity job-key)
               (j/store-durably))))

(defn- make-triggerable-job [job-id]
  (j/build (j/of-type JskTriggerJob)
           (j/using-job-data {"job-id" job-id "ignore-execution?" true}) ; string keys for quartz
           (j/with-identity (make-trigger-job-key job-id))
           (j/store-durably)))




;-----------------------------------------------------------------------
; Adds or replaces a job within the scheduler.
;-----------------------------------------------------------------------
(defn save-job! [{:keys [command-line job-id execution-directory]}]
  (add-job (make-shell-job job-id command-line execution-directory)))


;-----------------------------------------------------------------------
; Makes a trigger from a Schedule instance.
; Trigger-id is a string.
; Returns a trigger instance.
;-----------------------------------------------------------------------
(defn- make-cron-trigger
  "Makes a cron trigger instance based on the schedule specified."
  [trigger-id cron-expr job-id]

  (info "make-cron-trigger id " trigger-id ", cron: " cron-expr ", job-id:" job-id)

  (let [cron-sched (cron/schedule (cron/cron-schedule cron-expr))
        trigger-key (make-trigger-key trigger-id)
        job-key (make-trigger-job-key job-id)]

    (add-job (make-triggerable-job job-id))

    (t/build
     (t/with-identity trigger-key)
     (trigger-for-job-key job-key)
     (t/start-now)
     (t/with-schedule cron-sched))))


(defn- create-trigger-instances [job-id schedules]
  (let [args (map (juxt #(:job-schedule-id %) :cron-expression (constantly job-id)) schedules)]
    (map #(apply make-cron-trigger %) args)))

(defn schedule-cron-job! [job-id schedules]
  (doseq [t (create-trigger-instances job-id schedules)]
      (schedule-trigger t)))


;-----------------------------------------------------------------------
; Deletes triggers specified by the trigger-ids.
;-----------------------------------------------------------------------
(defn rm-triggers! [trigger-ids]
  (qs/delete-triggers (map make-trigger-key trigger-ids)))


;-----------------------------------------------------------------------
; Update triggers.
;-----------------------------------------------------------------------
(defn update-triggers! [schedule-infos]
  (doseq [{:keys [job-schedule-id job-id cron-expression]} schedule-infos]
    (let [t (make-cron-trigger job-schedule-id cron-expression job-id)]
      (reschedule-job t))))



;-----------------------------------------------------------------------
; WORKFLOW
;-----------------------------------------------------------------------
(defn register-workflow
  "Registers the workflow with jsk/quartz."
  [wf-id]


  )











