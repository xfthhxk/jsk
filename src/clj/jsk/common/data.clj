(ns jsk.common.data
  "Shared data (constants and such)")

(def synthetic-workflow-id 1)

(def job-type-id 1)
(def workflow-type-id 2)

(def app-edn "application/edn")


; These are tied to what is in the job_execution_status table
(def unexecuted-status 1)
(def started-status 2)
(def finished-success 3)
(def finished-error 4)
(def aborted-status 5)
(def unknown-status 6)
(def pending-status 7)
