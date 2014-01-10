(ns jsk.agent.ps
  "JSK process handling"
  (:require [clojure.java.io :as io]
            [taoensso.timbre :as log])

  (:import (org.zeroturnaround.exec ProcessExecutor ProcessResult StartedProcess)
           (java.io FileOutputStream)
           (java.util.concurrent TimeUnit)))



(def ^:private process-cache (atom {}))

(defn- ids->ps [exec-id id]
  (get-in @process-cache [exec-id id]))

(defn- cache-ps [exec-id id ^Process p]
  (assert (@process-cache exec-id)
          (str "Unknown exec-id:" exec-id))
  (assert (nil? (ids->ps exec-id id))
          (str "Already have mapping for exec-id:" exec-id ", id:" id))

  (swap! process-cache assoc-in [exec-id id] p))

(defn- rm-from-cache [exec-id id]
  (swap! process-cache update-in [exec-id] dissoc id))

(defn- all-ps
  "Answers with a map of exec-vertex-ids -> StartedProcess instances belonging to the execution
   specified by exec-id or an empty map."
  [exec-id]
  (let [ps-map (@process-cache exec-id)]
    (if ps-map
      ps-map
      {})))


(defn- end-execution-tracking* [exec-id]
  (swap! process-cache dissoc exec-id))

(defn tracking-execution?
  "Answers if the execution is being tracked."
  [exec-id]
  (-> (@process-cache exec-id) nil? not))

;-----------------------------------------------------------------------
; Kills all processes belonging to an execution or just the one item
; specified in the execution group. Answers with a seq of execution-vertex-ids
; corresponding to the processes killed.
;-----------------------------------------------------------------------
(defn kill!
  ([exec-id]
   (let [id-ps-map (all-ps exec-id)]

     ; destroy all of them
     (doseq [p (vals id-ps-map)]
       (-> p .destroy))

     (swap! process-cache assoc exec-id {}) ; clear out the mappings
     (keys id-ps-map))) ; answer with all the exec-vertex-ids

  ([exec-id id]
   (let [p (ids->ps exec-id id)]
     (-> p .destroy)
     (rm-from-cache exec-id id)
     (list id))))

(defn begin-execution-tracking
  "Begins tracking this execution.  Needed so that if an execution is aborted
   nothing else in the meantime sticks another process under exec-id."
  [exec-id]
  (swap! process-cache assoc exec-id {}))

(defn end-execution-tracking
  "Ends the execution tracking. Throws an error if any items are still in cache."
  [exec-id]

  (let [pss (all-ps exec-id)
        ps-count (count pss)]

    (assert (zero? ps-count)
            (str "Not all Process references removed for exec-id:" exec-id ", count=" ps-count))

    (end-execution-tracking* exec-id)))


; clojure java shell2 lib
(defn- create
  "Makes the proc executor instance required"
  [^String cmd-with-args ^String exec-dir ^FileOutputStream os]
  (doto (ProcessExecutor.)
    (.commandSplit cmd-with-args)
    (.directory (io/file exec-dir))
    (.redirectErrorStream true) ; output and error go to the same stream makes it easier to see what happened
    (.redirectOutput os)
    (.exitValueAny)))

(defn- run
  "Executes a process executor returning the result."
  [^ProcessExecutor pe]
  (let [^ProcessResult result (-> pe .execute)]
    { :exit-code (.exitValue result) }))

(defn exec
  ([cmd-with-args exec-dir process-output-file-name]
   "Takes a cmd with args to run and the directory in which it should be run"
   (with-open [os (FileOutputStream. process-output-file-name)]
     (run (create cmd-with-args exec-dir os)))))



(defn- start [exec-group-id exec-id timeout ^ProcessExecutor pe]
  (let [^StartedProcess sp (.start pe)]
    ; stick in cache so can be killed if need be by outside intervention
    (if (-> exec-group-id tracking-execution? not)
      (begin-execution-tracking exec-group-id))

    (cache-ps exec-group-id exec-id (.process sp))

    (try
      (let [^ProcessResult result (-> sp .future (.get timeout TimeUnit/SECONDS))]
        (rm-from-cache exec-group-id exec-id)
        (.exitValue result))
      (catch Exception e
        (rm-from-cache exec-group-id exec-id)
        (throw e)))))

(defn exec1
  "Takes a cmd with args to run and the directory in which it should be run"
  [exec-group-id exec-id timeout cmd-with-args exec-dir process-output-file-name]
   (with-open [os (FileOutputStream. process-output-file-name)]
     (start exec-group-id exec-id timeout (create cmd-with-args exec-dir os))))
