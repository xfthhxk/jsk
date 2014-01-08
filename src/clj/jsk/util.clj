(ns jsk.util
  (:require [jsk.user :as juser]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal)])
  (:import [java.util UUID]))


(defn validation-errors? [bouncer-result]
  (-> bouncer-result first nil? not))

(defn extract-validation-errors [bouncer-result]
  (first bouncer-result))

(defn make-error-response [errors]
  {:success? false :errors errors})


(def app-edn "application/edn")

(defn edn-request? [r]
  (let [ct (:content-type r)]
    (if ct
      (not= -1 (.indexOf ct app-edn))
      false)))

(defn nan? [x]
  (Double/isNaN x))

(def not-nan (complement nan?))


(defn ensure-directory [dir]
  (-> dir io/file .mkdirs))


(defn str->int [s]
  (Integer/parseInt s))

(def job-type-id 1)
(def workflow-type-id 2)

(defn workflow-type? [id]
  (= workflow-type-id id))

(defn job-type? [id]
  (= job-type-id id))



(defn uuid []
  (-> (UUID/randomUUID) .toString))

(defn now
  "Current time in millisecs"
  []
  (System/currentTimeMillis))


(defn jvm-instance-name
  "Gets the jvm instance name. Hopefully, it is pid@host."
  []
  (-> (java.lang.management.ManagementFactory/getRuntimeMXBean) .getName))


(defn start-thread [thread-name f]
  (.start (Thread. nil f (str "jsk-" thread-name))))




(def status-updates-topic "status-updates")
