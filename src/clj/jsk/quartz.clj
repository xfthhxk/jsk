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
  (:import (org.quartz CronExpression)))


(defn start []
  (qs/initialize)
  (qs/start))

(defn stop []
  (qs/shutdown))



(defprotocol StringMatcher
  "Search  various objects"
  (match? [this expr])
  (regex-match? [this expr])
  (fuzzy-match? [this expr]))

;-----------------------------------------------------------------------
; Didn't know about NativeJob. Maybe use that instead.
;-----------------------------------------------------------------------
(defjob ShellJob
  [ctx]
  (let [{:strs [ps argsv]} (qc/from-job-data ctx)]
    (info "ps: " ps " argsv: " argsv)
    (info "execution" (ps/exec ps argsv))))



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
   (let [job-map {"ps" ps-name "argsv" argsv}
         job-key (j/key job-id)]

     ; NB. j/build is a macro which creates and passes itself in using ->
     (j/build (j/of-type ShellJob)
              (j/using-job-data job-map)
              (j/with-identity job-key)))))

;-----------------------------------------------------------------------
; Makes a trigger from a Schedule instance.
; Trigger-id is a string.
; Returns a trigger instance.
;-----------------------------------------------------------------------
(defn make-cron-trigger
  "Makes a cron trigger instance based on the schedule specified."
  [trigger-id cron-expr]
  (let [cron-sched (cron/schedule (cron/cron-schedule cron-expr))
        trigger-key (t/key trigger-id)]
    (t/build
     (t/with-identity trigger-key)
     (t/start-now)
     (t/with-schedule cron-sched))))

; parse the executable and tokenize the rest to be argsv
; [cmd [args]]
(defn- parse-job-command-line [cmd-line]
  (let [v (filter (complement string/blank?) (string/split cmd-line #" "))]
    [(first v) (rest v)]))

(defn- create-job-instance [job]
  (info "creating job for: " job)
  (let [job-id (-> :job-id job str)
        [ps-name argsv] (-> :command-line job parse-job-command-line)]
    (make-shell-job job-id ps-name argsv)))

(defn- create-trigger-instances [schedules]
  (let [args (map (juxt #(-> % :job-schedule-id str) :cron-expression) schedules)]
    (map #(apply make-cron-trigger %) args)))

(defn schedule-cron-job! [job schedules]
  (let [job* (create-job-instance job)
        triggers (create-trigger-instances schedules)]
    (doseq [t triggers]
      (qs/schedule job* t))))


(defn rm-triggers! [trigger-ids]
  (doseq [tk (->> trigger-ids (map str) (map t/key))]
    (info "tk is " tk)
   (qs/delete-trigger tk)))

;-----------------------------------------------------------------------
; Answers if the cron expression is valid or not.
;-----------------------------------------------------------------------
(defn cron-expr? [expr]
  (if expr
    (CronExpression/isValidExpression expr)
    false))

;  (let [date-job (q/make-shell-job "date")
;        cal-job (q/make-shell-job "cal" [[1] [2012]])
;        trigger (q/make-trigger "triggers.1")]
;    (qs/schedule cal-job (q/make-trigger "triggers.1"))
;    (qs/schedule date-job (q/make-trigger "triggers.2")))
