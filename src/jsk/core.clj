(ns jsk.core
  "JSK core"
  (:require [jsk.ps :as ps]
            [taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal spy with-log-level)]
            [clojurewerkz.quartzite.scheduler :as qs]
            [clojurewerkz.quartzite.conversion :as qc]
            [clojurewerkz.quartzite.triggers :as t]
            [clojurewerkz.quartzite.jobs :as j]
            [clojurewerkz.quartzite.jobs :refer [defjob]]
            [clojurewerkz.quartzite.schedule.simple :refer [schedule with-repeat-count with-interval-in-milliseconds]])
  (:import (org.quartz.impl.matchers GroupMatcher EverythingMatcher)))


;; need to be able to run a job based on a schedule
;; job in this case is going to be a quartzite job which


(defjob ShellJob
  [ctx]
  (let [{:strs [ps argsv]} (qc/from-job-data ctx)]
    (info "ps: " ps " argsv: " argsv)
    (info "execution" (ps/exec ps argsv))))


(defn make-shell-job
  ([ps-name] (make-shell-job ps-name []))

  ([ps-name argsv]
   (let [job-map {"ps" ps-name "argsv" argsv}
         job-key (j/key ps-name)]

     ; NB. j/build is a macro which creates and passes itself in using ->
     (j/build (j/of-type ShellJob)
              (j/using-job-data job-map)
              (j/with-identity job-key)))))

(defn make-schedule []
  ; NB. schedule is a macro
  (schedule (with-repeat-count 1000)
            (with-interval-in-milliseconds 5000000)))

; NB. a trigger can't be shared by jobs
(defn make-trigger
  [t-name]
  (let [t-key (t/key t-name)
        t-schedule (make-schedule)]
    (t/build (t/with-identity t-key) ; NB build is a macro
             (t/start-now)
             (t/with-schedule t-schedule))))

(defn init
  [& args]
  (qs/initialize)
  (qs/start)
  (let [date-job (make-shell-job "date")
        cal-job (make-shell-job "cal" [[1] [2012]])
        trigger (make-trigger "triggers.1")]
    (qs/schedule cal-job (make-trigger "triggers.1"))
    (qs/schedule date-job (make-trigger "triggers.2"))))


(defn shutdown
  []
  (qs/shutdown))

(defn ls-jobs []
  (let [group-names (qs/get-job-group-names)
        job-keys (map #(-> %1 (GroupMatcher/groupEquals) (qs/get-job-keys) seq) group-names)
        ;;job-keys (map #(seq (qs/get-job-keys (GroupMatcher/groupEquals %1))) group-names)
        jk (flatten job-keys)]
    (info "a# job-keys is: " job-keys " jk is: " jk)
    (map #(.getName %1) jk)))





















