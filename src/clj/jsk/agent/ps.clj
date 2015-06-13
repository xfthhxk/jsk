(ns jsk.agent.ps
  "JSK process handling"
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [taoensso.timbre :as log])

  (:import (org.zeroturnaround.exec ProcessExecutor ProcessResult StartedProcess)
           (org.zeroturnaround.process PidUtil)
           (java.io FileOutputStream)
           (java.util.concurrent TimeUnit)))



(def ^:private process-cache (atom {}))

(defn process->os-pid
  "Gets the process id as an integer for the Process p."
  [^Process p]
  (PidUtil/getPid p))

(defn- ids->ps [execution-id exec-vertex-id]
  (get-in @process-cache [execution-id exec-vertex-id]))

(defn- cache-ps [execution-id exec-vertex-id ^Process p]
  (assert (@process-cache execution-id)
          (str "Unknown execution-id:" execution-id))
  (assert (nil? (ids->ps execution-id exec-vertex-id))
          (str "Already have mapping for execution-id:" execution-id ", exec-vertex-id:" exec-vertex-id))

  (swap! process-cache assoc-in [execution-id exec-vertex-id] p))

(defn- rm-from-cache
  "Removes the ps from the cache. Also removes the execution-id mapping if there are no more
   exec-vertex ids for the execution-id"
  [execution-id exec-vertex-id]
  (swap! process-cache (fn [ps-cache]
                         (let [ps-cache' (update-in ps-cache [execution-id] dissoc exec-vertex-id)]
                           (if (empty? (ps-cache' execution-id))
                             (dissoc ps-cache' execution-id)
                             ps-cache')))))

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


(defn send-posix-signal
  "Sends the signal specified to the process p by shelling out and invoking kill"
  [^String signal ^Process p]
  ;; get the process id
  ;; call kill by shelling out
 (let [pid (-> p process->os-pid str)]
   (log/infof "Sending signal %s to process pid %s" signal pid)
   (let [{:keys [exit out err]} (shell/sh "kill" signal pid)]
     (if (zero? exit)
       (log/infof "Signal %s successfully sent to process pid %s" signal pid)
       (do
         (log/warnf "%s exit-code: %s" pid exit)
         (log/warnf "%s stdout: %s" pid out)
         (log/warnf "%s stderr: %s" pid err)
         (throw (Exception. (format "Signal %s to process pid %s failed. %s" signal pid err))))))))


(def send-posix-stop-signal (partial send-posix-signal "-STOP"))
(def send-posix-cont-signal (partial send-posix-signal "-CONT"))

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


(defn pause!
  ([execution-id]
   (let [id-ps-map (all-ps execution-id)]
     (doseq [p (vals id-ps-map)]
       (send-posix-stop-signal p))))

  ([execution-id exec-vertex-id]
   (let [p (ids->ps execution-id exec-vertex-id)]
     (send-posix-stop-signal p))))

(defn resume!
  ([execution-id]
   (let [id-ps-map (all-ps execution-id)]
     (doseq [p (vals id-ps-map)]
       (send-posix-cont-signal p))))
  ([execution-id exec-vertex-id]
   (let [p (ids->ps execution-id exec-vertex-id)]
     (send-posix-cont-signal p))))

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
