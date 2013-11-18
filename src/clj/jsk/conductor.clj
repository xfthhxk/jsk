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
; {execution-id {job-id-1 {:exec-vertex-id 123
;                          :node-type 1
;                          :status 3
;                          :on-success #{2 3}
;                          :on-fail #{4}}}
;                {job-id-2 {:exec-vertex-id 124
;                          :node-type 1
;                          :status 3
;                          :on-success #{2 3}
;                          :on-fail #{4}}}
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


(defn- run-job-as-synthetic-wf [job-id]
  (let [{:keys[execution-id roots table] :as m} (w/setup-synthetic-execution job-id)]
    (swap! all-exec-tbl assoc execution-id table)
    (run-job job-id {:execution-id execution-id
                     :job-id job-id
                     :trigger-src :conductor
                     :exec-vertex-id (get-in table [job-id :exec-vertex-id])})))


(defn- run-jobs [job-ids execution-id tbl]
  (doseq [job-id job-ids
          :let [data {:execution-id execution-id
                      :job-id job-id
                      :trigger-src :conductor
                      :exec-vertex-id (get-in tbl [job-id :exec-vertex-id])}]]
    (run-job job-id data)))

;-----------------------------------------------------------------------
; Input map is something like:
;
; {:roots #{2}, :table {2 {:on-success #{1 3}}}}
;
; This is a workflow made of 3 jobs and terminates
; with the execution of 1 and 3.
;-----------------------------------------------------------------------
(defn- run-wf [wf-id]
  (put! @info-chan {:event :wf-started :wf-id wf-id})

  (try
    (let [{:keys[execution-id roots table] :as m} (w/setup-execution wf-id)]
      (debug "Running workflow with execution-id: " execution-id
             ", roots: " roots ", table: " table)

      (swap! all-exec-tbl assoc execution-id table)
      (run-jobs roots execution-id table))
    (catch Exception e
      (error e))))


(defn- update-job-status [{:keys[execution-id job-id status]}]
  (swap! all-exec-tbl update-in [execution-id job-id] merge {:status status}))

(defn- trigger-successor-jobs [{:keys[execution-id job-id success?]}]
  (let [what-next (if success? :on-success :on-fail)
        tbl (get @all-exec-tbl execution-id)
        next-jobs (get-in tbl [job-id what-next])]
    (info "execution-id " execution-id ", next jobs: " next-jobs)
    (run-jobs next-jobs execution-id tbl)))

(defn- check-wf-finished [msg]
  )


;-----------------------------------------------------------------------
; Schedule job to be triggered now.
;-----------------------------------------------------------------------
(defn trigger-job-now [job-id]
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



(defmulti dispatch :event)

(defmethod dispatch :trigger-job [{:keys [job-id trigger-src]}]
  (case trigger-src
    :quartz (run-job-as-synthetic-wf job-id)
    :user   (run-job-as-synthetic-wf job-id)
    :conductor (run-job job-id)))


(defmethod dispatch :trigger-wf [{:keys [wf-id]}]
  (run-wf wf-id))

; this comes from execution
; update status for the execution's job
(defmethod dispatch :job-started [msg]
  (update-job-status msg))

; this comes from execution
; update status for the execution's job
; figure out next set of jobs to execute based on
; whether job finished successfully and has
; deps
(defmethod dispatch :job-finished [msg]
  (update-job-status msg)
  (trigger-successor-jobs msg)
  (check-wf-finished msg))


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










