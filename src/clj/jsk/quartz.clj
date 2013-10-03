(ns jsk.quartz
  "JSK quartz"
  (:require [jsk.ps :as ps]
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
;-----------------------------------------------------------------------
(defn make-shell-job
  ([ps-name] (make-shell-job ps-name []))

  ([ps-name argsv]
   (let [job-map {"ps" ps-name "argsv" argsv}
         job-key (j/key ps-name)]

     ; NB. j/build is a macro which creates and passes itself in using ->
     (j/build (j/of-type ShellJob)
              (j/using-job-data job-map)
              (j/with-identity job-key)))))

;-----------------------------------------------------------------------
; Makes a trigger from a Schedule instance.
;-----------------------------------------------------------------------
(defn make-cron-trigger
  "Makes a cron trigger instance based on the schedule specified."
  [trigger-key schedule]
  (cron/schedule (cron/cron-schedule (:cron-expr schedule))))


(defn valid-cron-expr? [expr]
  (CronExpression/isValidExpression expr))


