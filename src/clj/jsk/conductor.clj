(ns jsk.conductor
  "Coordination of workflows."
  (:require
            [jsk.quartz :as q]
            [clojurewerkz.quartzite.conversion :as qc]
            [jsk.workflow :as w]
            [jsk.db :as db]
            [clojurewerkz.quartzite.scheduler :as qs]
            [clojure.string :as str]
            [clojure.core.async :refer [chan go go-loop put! >! <!]]
            [taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal)])
  (:import (org.quartz JobDataMap JobDetail JobExecutionContext JobKey Scheduler)))

;-----------------------------------------------------------------------
; exec-tbl tracks executions and the status of each job in the workflow.
; It is also used to determine what job(s) to trigger next.
; {execution-id {:nodes {job-id-1 {:exec-vertex-id 123
;                                  :node-type 1
;                                  :on-success #{2 3}
;                                  :on-fail #{4}}
;                       {job-id-2 {:exec-vertex-id 124
;                                  :node-type 1
;                                  :on-success #{2 3}
;                                  :on-fail #{4}}}}
;                :running-jobs-count 1
;                :failed? false}
;
;                       ....... etc. .........   }
;
; {:roots #{2}, :table {2 {:on-success #{1 3}}}}
;
; This is a workflow made of 3 jobs and terminates
; with the execution of 1 and 3.
;-----------------------------------------------------------------------
(def all-exec-tbl (atom {}))

(def cond-chan (atom nil))
(def info-chan (atom nil))

(defn- execute-job  [^JobKey job-key ^JobDataMap job-data-map]
    (.triggerJob ^Scheduler @qs/*scheduler* job-key job-data-map))

(defn- run-job
  ([job-id] (run-job job-id {}))
  ([job-id data]
    (execute-job (q/make-job-key job-id) (qc/to-job-data data))))

(defn- add-to-exec-tbl!
  "Adds the execution-id and table to the all-exec-tbl. Also
   initailizes the running jobs count property."
  [execution-id table]
  (swap! all-exec-tbl assoc execution-id {:nodes table :running-jobs-count 0 :failed? false}))

(defn- run-jobs [job-ids execution-id]
  "Fires off each job in job-ids.  execution-id is required to figure out
   the exec-vertex-id which serves as the unique id for writing log files
   to.  exec-vertex-id is used by the execution.clj ns."

  (let [tbl (get-in @all-exec-tbl [execution-id :nodes])]
    (doseq [job-id job-ids
            :let [data {:execution-id execution-id
                        :job-id job-id
                        :trigger-src :conductor
                        :exec-vertex-id (get-in tbl [job-id :exec-vertex-id])}]]
      (run-job job-id data))))

(defn- successor-jobs
  "Answers with the successor jobs for the execution status of job-id
  belonging to execution-id."
  [{:keys[execution-id job-id success?]}]
  (let [what-next (if success? :on-success :on-fail)]
    (get-in @all-exec-tbl [execution-id :nodes job-id what-next])))

;-----------------------------------------------------------------------
; Creates a synthetic workflow to run the specified job in.
;-----------------------------------------------------------------------
(defn- run-job-as-synthetic-wf
  "Runs the job in the context of a synthetic workflow."
  [job-id]
  (let [{:keys[execution-id roots table] :as m} (w/setup-synthetic-execution job-id)]
    (add-to-exec-tbl! execution-id table)
    (run-jobs #{job-id} execution-id)))


;-----------------------------------------------------------------------
; Input map is something like:
;
; {:roots #{2}, :table {2 {:on-success #{1 3}}}}
;
; This is a workflow made of 3 jobs and terminates
; with the execution of 1 and 3.
;-----------------------------------------------------------------------
(defn- run-wf [wf-id]
  (try
    (let [{:keys[execution-id roots table] :as m} (w/setup-execution wf-id)]
      (debug "Running workflow with execution-id: " execution-id
             ", roots: " roots ", table: " table)
      (put! @info-chan {:event :wf-started :wf-id wf-id :execution-id execution-id})
      (add-to-exec-tbl! execution-id table)
      (run-jobs roots execution-id))
    (catch Exception e
      (error e))))



;-----------------------------------------------------------------------
; Schedule job to be triggered now.
;-----------------------------------------------------------------------
(defn trigger-job-now [job-id]
  (info "job-id is " job-id)
  (put! @cond-chan {:event :trigger-job :job-id job-id :trigger-src :user})
  true)

;-----------------------------------------------------------------------
; Schedule workflow to be triggered now.
;-----------------------------------------------------------------------
(defn trigger-workflow-now
  [wf-id]
  (info "Triggering workflow with id: " wf-id)
  (put! @cond-chan {:event :trigger-wf :wf-id wf-id})
  true)


;-----------------------------------------------------------------------
; Update running jobs count for the msg. f is a fn such as inc / dec
; for how to update the count.  Answers with the new value for
; the running job count.
;-----------------------------------------------------------------------
(defn- update-running-jobs-count!
  "Updates the running jobs count based on the value of :execution-id.
  'f' is a fn such as inc/dec for updating the count.  Answers with the
  new :running-jobs-count for the execution-id."
  [execution-id f]
  (let [path [execution-id :running-jobs-count]]
    (-> (swap! all-exec-tbl update-in path f)
        (get-in path))))


(defmulti dispatch :event)

(defmethod dispatch :trigger-job [{:keys [job-id trigger-src]}]
  (case trigger-src
    :quartz (run-job-as-synthetic-wf job-id)
    :user   (run-job-as-synthetic-wf job-id)
    :conductor (run-job job-id)))


(defmethod dispatch :trigger-wf [{:keys [wf-id]}]
  (run-wf wf-id))

; this comes from execution.clj
(defmethod dispatch :job-started [{:keys[execution-id]}]
  (update-running-jobs-count! execution-id inc))

; this comes from execution.clj
; decrements the running job count for the execution-id
; determines next set of jobs to run
; also determines if the workflow is finished and/or errored.
(defmethod dispatch :job-finished [{:keys[execution-id job-id success?] :as msg}]
  (let [new-count (update-running-jobs-count! execution-id dec)
        next-jobs (successor-jobs msg)
        wf-fail? (and (not success?) (empty? next-jobs))
        wf-finished? (and (zero? new-count) (empty? next-jobs))]

    (if wf-fail?
      (swap! all-exec-tbl update-in [execution-id] assoc :failed true))

    (if wf-finished?
      (let [wf-success? (not (get-in @all-exec-tbl [execution-id :failed]))
            ts (db/now)]
        (info "workflow with execution-id " execution-id " finished, success? " wf-success?)
        (db/workflow-finished execution-id wf-success? ts)
        (put! @info-chan {:event :wf-finished :execution-id execution-id :success? wf-success?}))
      (run-jobs next-jobs execution-id))))



(defn init
  "Sets up a queue which other parts of the program can write
   messages to. A message is a map with at least an :event key.
   Rest of the keys will depend on the event and what's relevant."
  [cond-ch info-ch]

  (reset! cond-chan cond-ch)
  (reset! info-chan info-ch)

  (go-loop [msg (<! cond-ch)]
     (dispatch msg)
     (recur (<! cond-ch))))










