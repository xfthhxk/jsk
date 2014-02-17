(ns jsk.common.util
  (:require [jsk.common.data :as data]
            [clojure.java.io :as io]
            [taoensso.timbre :as log])
  (:import [java.util UUID]))

;-----------------------------------------------------------------------
; Answers if the cron expression is valid or not.
;-----------------------------------------------------------------------
(defn cron-expr? [expr]
  (if expr
    (org.quartz.CronExpression/isValidExpression expr)
    false))

(defn validation-errors? [bouncer-result]
  (-> bouncer-result first nil? not))

(defn extract-validation-errors [bouncer-result]
  (first bouncer-result))

(defn make-error-response [errors]
  {:success? false :errors errors})


(defn edn-request? [r]
  (let [ct (:content-type r)]
    (if ct
      (not= -1 (.indexOf ct data/app-edn))
      false)))

(defn nan? [x]
  (Double/isNaN x))

(def not-nan? (complement nan?))


(defn ensure-directory [dir]
  (-> dir io/file .mkdirs))

(def present? (complement nil?))


(defn str->int [s]
  (Integer/parseInt s))


(defn workflow-type? [id]
  (= data/workflow-type-id id))

(defn job-type? [id]
  (= data/job-type-id id))

(def ^:private node-type-id-kw-map {data/job-type-id :job
                                    data/workflow-type-id :workflow})
(defn node-type-id->kw [id]
  (get node-type-id-kw-map id))


(defn uuid []
  (-> (UUID/randomUUID) .toString))

(defn now-ms
  "Current time in millisecs"
  []
  (System/currentTimeMillis))

; current date time
(defn now
  "Current time as a java Date instance."
  []
  (java.util.Date.))


(defn jvm-instance-name
  "Gets the jvm instance name. Hopefully, it is pid@host."
  []
  (-> (java.lang.management.ManagementFactory/getRuntimeMXBean) .getName))


(defn start-thread [thread-name f]
  (.start (Thread. nil f (str "jsk-" thread-name))))

(defn periodically
  "Starts a new thread and every period ms calls f.
   f is a no arg function."
  [thread-name period f]
  (start-thread thread-name (fn []
                              (while true
                                (f)
                                (Thread/sleep period)))))

(defn select-filter
  "Selects entries from a map m based on predicate pred.
   pred is a two arg function which is passed the key and value for
   each map entry.  Returns a map with only those entries that
   satisfied pred."
  [pred m]
  (reduce (fn [ans [k v]]
            (if (pred k v)
              (assoc ans k v)
              ans))
          {}
          (seq m)))
