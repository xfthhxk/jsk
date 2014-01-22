(ns jsk.agent.events
  (:require [jsk.common.util :as util]
            [clojure.core.async :refer [put! <! go-loop chan]]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]))

;-----------------------------------------------------------------------
; event log format is: a clojure map per line with keys :msg and :exec-vertex-id
;-----------------------------------------------------------------------

(def ^:private log-file-name "./data/events-log.clj")

(def ^:private data-writer (atom nil))

(defn- setup-writer!
  "Sets up a writer or closes the existing writer and creates a new one with
  the append mode set to append?"
  [append?]
  (when @data-writer
    (.close @data-writer))

  (let [w (io/writer log-file-name :append append?)]
    (reset! data-writer w)))

(defn init!
  "Initializes the data writer."
  []
  (locking data-writer
    (assert (not @data-writer) "data-writer is already initialized!")
    (setup-writer! true)))

(defn destroy!
  "Call when shutting down."
  []
  (locking data-writer
    (when @data-writer
      (.close @data-writer)
      (reset! data-writer nil))))

(defn persist!
  "Persists the data to the events log file."
  ([{:keys [msg exec-vertex-id] :as data}]
     (assert data "nil data")
     (assert msg (str "nil :msg in " data))
     (assert exec-vertex-id (str "nil :exec-vertex-id in " data))

     (locking data-writer
       (doto @data-writer
         (.write (prn-str data))
         (.flush)))))



(defn- unackd-events
  "Reads and returns the events log data as a map.
   Returned map has the keys :job-aborted and :job-finished. The values for each
   is the actual message sent to the conductor for which no ack was received.
   This method only returns messages for which acks were not received."
  ([]
    (let [ack-map {:job-aborted-ack :job-aborted :job-finished-ack :job-finished}
          data {:job-aborted {} :job-finished {}}]
      (with-open [rdr (io/reader log-file-name)]
        (reduce (fn [m {:keys [msg exec-vertex-id] :as data-map}]
                  (cond
                   (contains? m msg) (assoc-in m [msg exec-vertex-id] data-map)
                   (contains? ack-map msg) (update-in m [(ack-map msg)] dissoc exec-vertex-id)
                   :else (throw (AssertionError. (str "Unknown event-kw: " msg)))))
                data
                (map read-string (line-seq rdr))))))
  ([_]
     (let [{:keys [job-aborted job-finished]} (unackd-events)]
       (concat (vals job-aborted) (vals job-finished)))))


(defn purge-ackd!
  "Purges data from the event log for which acks have been received."
  []
  (locking data-writer
    (destroy!)
    (let [ee (unackd-events 1)
          tmp-file-nm (str log-file-name ".tmp")]

      ; write to the tmp file
      (with-open [tmp-writer (io/writer tmp-file-nm)]
        (doseq [e ee]
          (.write tmp-writer (prn-str e))))

      ; copy tmp file to the log file and delete the tmp file
      (io/copy (io/file tmp-file-nm) (io/file log-file-name))
      (io/delete-file tmp-file-nm)

      ; setup the data-writer again
      (setup-writer! true))))
