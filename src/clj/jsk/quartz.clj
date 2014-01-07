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

(def quartz-channel (atom nil))

(defprotocol IKeyPredicates
  (exists? [item-key]
    "Answers if the item with item-key exists in the scheduler."))

(extend-protocol IKeyPredicates

  JobKey

  (exists? [jk]
    (.checkExists ^Scheduler @qs/*scheduler* jk))

  TriggerKey

  (exists? [tk]
    (.checkExists ^Scheduler @qs/*scheduler* tk)))

;-----------------------------------------------------------------------
; Quartz interop section.
;-----------------------------------------------------------------------

(defn- add-job [^JobDetail job-detail]
  (.addJob ^Scheduler @qs/*scheduler* job-detail true))

(defn- schedule-trigger [^Trigger trigger]
  (.scheduleJob ^Scheduler @qs/*scheduler* trigger))

(defn- reschedule-trigger
  ([^Trigger new-trigger] (reschedule-trigger (.getKey new-trigger) new-trigger))
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


(defn init [quartz-ch]
  (reset! quartz-channel quartz-ch)
  (qs/initialize))

(defn start []
  (qs/start))

(defn stop []
  (qs/shutdown))


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


; NB node-id has to be unique across jobs *and* workflows
(defn- make-triggerable-job [node-id job-key]
  (j/build (j/of-type JskTriggerJob)
           (j/using-job-data {"node-id" node-id}) ; string keys for quartz
           (j/with-identity job-key)
           (j/store-durably)))


;-----------------------------------------------------------------------
; Makes a trigger from a Schedule instance.
; Trigger-id is a string.
; Returns a trigger instance.
;-----------------------------------------------------------------------
(defn- make-cron-trigger
  "Makes a cron trigger instance based on the schedule specified."
  [trigger-key cron-sched job-key]
  (t/build
    (t/with-identity trigger-key)
    (trigger-for-job-key job-key)
    (t/start-now)
    (t/with-schedule cron-sched)))

(defn- make-cron-schedule
  "Makes a cron schedule for the cron-expr"
  [cron-expr]
  (-> cron-expr cron/cron-schedule cron/schedule))


(defn- persist-job
  "Saves the job to quartz if it doesn't already exist."
  [node-id job-key]
  (if (-> job-key exists? not)
      (add-job (make-triggerable-job node-id job-key))))


(defn- persist-trigger
  "Schedules the job in quartz either by adding the trigger or rescheduling the trigger."
  [t-key j-key cron-sched]
  (let [ct (make-cron-trigger t-key cron-sched j-key)
        f (if (exists? t-key) reschedule-trigger schedule-trigger)]
    (f ct)))


(defn schedule-cron-job!
  "Schedules the job using the arguments specified."
  [trigger-id node-id cron-expr]

  (log/info "Scheduling: trigger:" trigger-id ", node-id:" node-id ", cron:" cron-expr)

  (let [t-key (make-trigger-key trigger-id)
        j-key (make-trigger-job-key node-id)
        cron-sched (make-cron-schedule cron-expr)]
    (persist-job node-id j-key) ; saves node if necessary
    (persist-trigger t-key j-key cron-sched)))

;-----------------------------------------------------------------------
; Deletes triggers specified by the trigger-ids.
;-----------------------------------------------------------------------
(defn rm-triggers! [trigger-ids]
  (log/info "Deleting triggers: " trigger-ids)
  (qs/delete-triggers (map make-trigger-key trigger-ids)))





