(ns jsk.agent.ps
  "JSK process handling"
  (:require [clojure.java.io :as io]
            [taoensso.timbre :as log])

  (:import (org.zeroturnaround.exec ProcessExecutor ProcessResult StartedProcess)
           (java.io FileOutputStream)
           (java.util.concurrent TimeUnit)))



(def ^:private process-cache (atom {}))

(defn- ids->ps [execution-id exec-vertex-id]
  (get-in @process-cache [execution-id exec-vertex-id]))

(defn- cache-ps [execution-id exec-vertex-id ^Process p]
  (assert (@process-cache execution-id)
          (str "Unknown execution-id:" execution-id))
  (assert (nil? (ids->ps execution-id exec-vertex-id))
          (str "Already have mapping for execution-id:" execution-id ", exec-vertex-id:" exec-vertex-id))

  (swap! process-cache assoc-in [execution-id exec-vertex-id] p))

(defn- rm-from-cache [execution-id exec-vertex-id]
  (swap! process-cache update-in [execution-id] dissoc exec-vertex-id))

(defn- all-ps
  "Answers with a map of exec-vertex-ids -> StartedProcess instances belonging to the execution
   specified by exec-id or an empty map."
  [execution-id]
  (let [ps-map (@process-cache execution-id)]
    (if ps-map
      ps-map
      {})))


(defn- end-execution-tracking* [execution-id]
  (swap! process-cache dissoc execution-id))

(defn tracking-execution?
  "Answers if the execution is being tracked."
  [execution-id]
  (-> (@process-cache execution-id) nil? not))

;-----------------------------------------------------------------------
; Kills all processes belonging to an execution or just the one item
; specified in the execution group. Answers with a seq of execution-vertex-ids
; corresponding to the processes killed.
;-----------------------------------------------------------------------
(defn kill!
  ([execution-id]
   (let [id-ps-map (all-ps execution-id)]

     ; destroy all of them
     (doseq [p (vals id-ps-map)]
       (-> p .destroy))

     (swap! process-cache assoc execution-id {}) ; clear out the mappings
     (keys id-ps-map))) ; answer with all the exec-vertex-ids

  ([execution-id exec-vertex-id]
   (let [p (ids->ps execution-id exec-vertex-id)]
     (-> p .destroy)
     (rm-from-cache execution-id exec-vertex-id)
     (list exec-vertex-id))))

(defn begin-execution-tracking
  "Begins tracking this execution.  Needed so that if an execution is aborted
   nothing else in the meantime sticks another process under exec-id."
  [execution-id]
  (swap! process-cache assoc execution-id {}))

(defn end-execution-tracking
  "Ends the execution tracking. Throws an error if any items are still in cache."
  [execution-id]

  (let [pss (all-ps execution-id)
        ps-count (count pss)]

    (assert (zero? ps-count)
            (str "Not all Process references removed for execution-id:" execution-id ", count=" ps-count))

    (end-execution-tracking* execution-id)))


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



(defn- start [execution-id exec-vertex-id timeout ^ProcessExecutor pe]
  (let [^StartedProcess sp (.start pe)]
    ; stick in cache so can be killed if need be by outside intervention
    (if (-> execution-id tracking-execution? not)
      (begin-execution-tracking execution-id))

    (cache-ps execution-id exec-vertex-id (.process sp))

    (try
      (let [^ProcessResult result (-> sp .future (.get timeout TimeUnit/SECONDS))]
        (rm-from-cache execution-id exec-vertex-id)
        (.exitValue result))
      (catch Exception e
        (rm-from-cache execution-id exec-vertex-id)
        (throw e)))))

(defn exec1
  "Takes a cmd with args to run and the directory in which it should be run"
  [execution-id exec-vertex-id timeout cmd-with-args exec-dir process-output-file-name]
   (with-open [os (FileOutputStream. process-output-file-name)]
     (start execution-id exec-vertex-id timeout (create cmd-with-args exec-dir os))))
