(ns jsk.quartz
  "JSK quartz"
  (:require [jsk.ps :as ps]
            [clojure.string :as string]
            [taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal spy with-log-level)]
            [clojurewerkz.quartzite.scheduler :as qs]
            [clojurewerkz.quartzite.conversion :as qc]
            [clojurewerkz.quartzite.triggers :as t]
            [clojurewerkz.quartzite.jobs :as j]
            [clojurewerkz.quartzite.jobs :refer [defjob]]
            [clojurewerkz.quartzite.schedule.cron :as cron]
            [clojurewerkz.quartzite.schedule.simple :as simple])
  (:import (org.quartz.impl.matchers GroupMatcher EverythingMatcher))
  (:import (org.quartz CronExpression JobDetail JobKey Scheduler Trigger TriggerBuilder TriggerKey)))


(defn start []
  (qs/initialize)
  (qs/start))

(defn stop []
  (qs/shutdown))


(defn- add-job [^JobDetail job-detail]
  (.addJob ^Scheduler @qs/*scheduler* job-detail true))

(defn- schedule-trigger [^Trigger trigger]
  (.scheduleJob ^Scheduler @qs/*scheduler* trigger))

(defn- reschedule-job [^TriggerKey tkey ^Trigger new-trigger]
  (.rescheduleJob ^Scheduler @qs/*scheduler* tkey new-trigger))

;-----------------------------------------------------------------------
; Answers if the cron expression is valid or not.
;-----------------------------------------------------------------------
(defn cron-expr? [expr]
  (if expr
    (CronExpression/isValidExpression expr)
    false))

;-----------------------------------------------------------------------
; Didn't know about NativeJob. Maybe use that instead.
;-----------------------------------------------------------------------
(defjob ShellJob
  [ctx]
  (let [{:strs [ps argsv]} (qc/from-job-data ctx)]
    (info "ps: " ps " argsv: " argsv)
    (info "execution" (ps/exec ps argsv))))


(defn- make-job-key [id]
  (info "make-job-key id: " id)
  (j/key (str id) "jsk-job"))

(defn- make-trigger-key [id]
  (t/key (str id) "jsk-trigger"))

;-----------------------------------------------------------------------
; Creates a ShellJob instance by specifying JobData
; ie the arguments and the key
;
; job-id is a string
;
; Returns a job instance
;-----------------------------------------------------------------------
(defn make-shell-job
  ([job-id ps-name] (make-shell-job ps-name []))

  ([job-id ps-name argsv]
   (let [job-map {"ps" ps-name "argsv" argsv} ; have to use string keys for quartz
         job-key (make-job-key job-id)]

     ; NB. j/build is a macro which creates and passes itself in using ->
     (j/build (j/of-type ShellJob)
              (j/using-job-data job-map)
              (j/with-identity job-key)
              (j/store-durably)))))



; parse the executable and tokenize the rest to be argsv
; [cmd [args]]
(defn- parse-job-command-line [cmd-line]
  (let [v (filter (complement string/blank?) (string/split cmd-line #" "))]
    [(first v) (rest v)]))

(defn- create-job-instance [job]
  (info "creating job for: " job)
  (let [job-id (:job-id job)
        [ps-name argsv] (-> :command-line job parse-job-command-line)]
    (make-shell-job job-id ps-name argsv)))

(defn save-job! [job]
  (add-job (create-job-instance job)))


(defn- trigger-for-job-key [^TriggerBuilder tb ^JobKey job-key]
  (.forJob tb job-key))

;-----------------------------------------------------------------------
; Makes a trigger from a Schedule instance.
; Trigger-id is a string.
; Returns a trigger instance.
;-----------------------------------------------------------------------
(defn make-cron-trigger
  "Makes a cron trigger instance based on the schedule specified."
  [trigger-id cron-expr job-id]
  (info "make-cron-trigger id " trigger-id ", cron: " cron-expr ", job-id:" job-id)
  (let [cron-sched (cron/schedule (cron/cron-schedule cron-expr))
        trigger-key (make-trigger-key trigger-id)
        job-key (make-job-key job-id)]
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


(defn rm-triggers! [trigger-ids]
  (doseq [tk (->> trigger-ids (map str) (map t/key))]
    (info "tk is " tk)
   (qs/delete-trigger tk)))


;-----------------------------------------------------------------------
; Update triggers.
;-----------------------------------------------------------------------
(defn update-triggers [schedule-infos]
  (doseq [{:keys [job-schedule-id job-id cron-expression]} schedule-infos]
    (let [t (make-cron-trigger job-schedule-id cron-expression job-id)]
      (reschedule-job (.getKey t) t))))















