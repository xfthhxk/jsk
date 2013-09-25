(ns jsk.ps
  "JSK process"
  (:import (org.zeroturnaround.exec ProcessExecutor)))

(defn- create
  "Makes the proc executor instance required
   p is a string and argsv is a vector of vectors"
  [p argsv]
  (let [pe (ProcessExecutor.)
        p+args (map str (-> argsv flatten (conj p)))] ; only takes string args
   (.command pe p+args)))

(defn- run
  "Executes a process executor returning the result."
  [pe]
  (let [pr (-> pe (.readOutput true) .exitValueAny .execute)]
    {:output (.outputUTF8 pr)
     :exit-code (.exitValue pr)}))

(defn exec
  ([ps-name]
   "Takes a string executable name and runs it without any arguments"
   (exec ps-name []))

  ([ps-name argsv]
   "Takes a string ps-name and arguments to the process as a vector of vectors.
   Executes the process and returns the result.
   argsv can be [] or [[1]] or [[1] ['-o' 'vi']] for example."
   (run (create ps-name argsv))))
