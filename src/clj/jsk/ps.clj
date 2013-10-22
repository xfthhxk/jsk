(ns jsk.ps
  "JSK process"
  (:require [clojure.java.io :as io])
  (:import (org.zeroturnaround.exec ProcessExecutor ProcessResult)
           (java.io FileOutputStream)))

(defn- create
  "Makes the proc executor instance required"
  [^String cmd-with-args ^String exec-dir ^FileOutputStream os]
  (doto (ProcessExecutor.)
    (.commandSplit cmd-with-args)
    (.directory (io/file exec-dir))
    (.redirectErrorStream true) ; output and error go to the same stream makes it easier to see what happened
    (.redirectOutput os)
    (.exitValueNormal)))

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
