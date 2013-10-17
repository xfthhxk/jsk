(ns jsk.ps
  "JSK process"
  (:import (org.zeroturnaround.exec ProcessExecutor ProcessResult)))

(defn- create
  "Makes the proc executor instance required"
  [^String cmd-with-args ^String exec-dir]
  (doto (ProcessExecutor.)
    (.commandSplit cmd-with-args)
    (.directory (java.io.File. exec-dir))))

(defn- run
  "Executes a process executor returning the result."
  [^ProcessExecutor pe]
  (let [^ProcessResult result (-> pe (.readOutput true) .exitValueAny .execute)]
    {:output (.outputUTF8 result)
     :exit-code (.exitValue result)}))

(defn exec
  ([cmd-with-args exec-dir]
   "Takes a cmd with args to run and the directory in which it should be run"
   (run (create cmd-with-args exec-dir))))
