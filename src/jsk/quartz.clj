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
  (:import (org.quartz.impl.matchers GroupMatcher EverythingMatcher)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; In memory map of ids => Schedule instances
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def schedule-map (ref {}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; In memory map of names to ids
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def schedule-name-map (ref {}))

(def schedule-id (atom 0))

(defn- next-schedule-id []
  (swap! schedule-id inc))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schedules should be defined in the system
;; so that they can be referenced when creating a job.
;; The schedule can be used to create triggers when a job is created.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord Schedule [id name desc cron-expr])

(defprotocol StringMatcher
  "Search  various objects"
  (match? [this expr])
  (regex-match? [this expr])
  (fuzzy-match? [this expr]))

(extend-type Schedule
  StringMatcher
  (match? [this expr]
    (.startsWith (:name this) expr))
  (regex-match? [this expr]
    (.matches (:name this) expr))
  (fuzzy-match? [this expr]
    (warn "fuzzy-match? not implemented")
    false))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Adds the schedule to the internal data store
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn add-schedule!
  "Adds the schedule s to the internal store"
  [sname sdesc cron-expr]
  (let [id (next-schedule-id)
        s (Schedule. id sname sdesc cron-expr)]
    (dosync
     (commute schedule-map assoc id s)
     (commute schedule-name-id-map assoc sname id))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Find all schedules matching
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn find-schedules
  ([] (vals @schedule-map))
  ([match-fn expr]
   (filter #(match-fn %1 expr) (vals @schedule-map))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Answers true if a schedule with name sn exists.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn schedule-name-exists? [sn]
  (contains? @schedule-name-id-map sn))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Didn't know about NativeJob. Maybe use that instead.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defjob ShellJob
  [ctx]
  (let [{:strs [ps argsv]} (qc/from-job-data ctx)]
    (info "ps: " ps " argsv: " argsv)
    (info "execution" (ps/exec ps argsv))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Creates a ShellJob instance by specifying JobData
;; ie the arguments and the key
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-shell-job
  ([ps-name] (make-shell-job ps-name []))

  ([ps-name argsv]
   (let [job-map {"ps" ps-name "argsv" argsv}
         job-key (j/key ps-name)]

     ; NB. j/build is a macro which creates and passes itself in using ->
     (j/build (j/of-type ShellJob)
              (j/using-job-data job-map)
              (j/with-identity job-key)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Makes a trigger from a Schedule instance.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-cron-trigger
  "Makes a cron trigger instance based on the schedule specified."
  [trigger-key schedule]
  (cron/schedule (cron/cron-schedule (:cron-expr schedule))))

(defn make-schedule []
  ; NB. schedule is a macro
  (simple/schedule (simple/with-repeat-count 1000)
            (simple/with-interval-in-milliseconds 5000000)))

; NB. a trigger can't be shared by jobs
(defn make-trigger
  [t-name]
  (let [t-key (t/key t-name)
        t-schedule (make-schedule)]
    (t/build (t/with-identity t-key) ; NB build is a macro
             (t/start-now)
             (t/with-schedule t-schedule))))


(defn ls-jobs []
  (let [group-names (qs/get-job-group-names)
        job-keys (map #(-> %1 (GroupMatcher/groupEquals) (qs/get-job-keys) seq) group-names)
        jk (flatten job-keys)]
    (info "a# job-keys is: " job-keys " jk is: " jk)
    (map #(.getName %1) jk)))

(defn ls-triggers []
  (let [group-names (qs/get-trigger-group-names)
        trigger-keys (map #(-> %1 (GroupMatcher/groupEquals) (qs/get-trigger-keys) seq) group-names)
        jk (flatten trigger-keys)]
    (info "a# trigger-keys is: " trigger-keys " jk is: " jk)
    (map #(.getName %1) jk)))
