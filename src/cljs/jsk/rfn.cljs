;----------------------------------------------------------------------
; Async remote function calls.  Uses rpc namespace.
; These all return a core.async channel.
;----------------------------------------------------------------------
(ns jsk.rfn
  (:require [jsk.rpc  :as rpc]
            [jsk.util :as u]))

;----------------------------------------------------------------------
; Node functions
;----------------------------------------------------------------------
(defn fetch-all-nodes []
  (rpc/GET "/nodes"))

;----------------------------------------------------------------------
; Job functions
;----------------------------------------------------------------------
(defn trigger-job-now [job-id]
  (let [url (str "/jobs/trigger-now/" job-id)]
    (rpc/GET url)))


(defn fetch-all-jobs []
  (rpc/GET "/jobs"))


(defn fetch-job-details [job-id]
  (rpc/GET (str "/jobs/" job-id)))


(defn save-job [job]
  (rpc/POST "/jobs/save" job))


(defn fetch-all-schedules []
  (rpc/GET "/schedules"))


(defn fetch-schedule-associations [job-id]
  (rpc/GET (str "/jobs/sched-assoc/" job-id)))

(defn save-job-schedule-associations [data]
  (rpc/POST "/jobs/assoc" data))

;----------------------------------------------------------------------
; Schedule functions
;----------------------------------------------------------------------
(defn fetch-all-schedules []
  (rpc/GET "/schedules"))

(defn fetch-schedule-details [schedule-id]
  (rpc/GET (str "/schedules/" schedule-id)))

(defn save-schedule [data]
  (rpc/POST "/schedules/save" data))

;----------------------------------------------------------------------
; Workflow functions
;----------------------------------------------------------------------
(defn fetch-all-workflows []
  (rpc/GET "/workflows"))

(defn fetch-workflow-details [id]
  (rpc/GET (str "/workflows/" id)))

(defn fetch-workflow-graph [id]
  (rpc/GET (str "/workflows/graph/" id)))

(defn save-workflow [data]
  (rpc/POST "/workflows/save" data))

(defn trigger-workflow-now [wf-id]
  (let [url (str "/workflows/trigger-now/" wf-id)]
    (rpc/GET url)))


;----------------------------------------------------------------------
; Execution functions
;----------------------------------------------------------------------
(defn fetch-execution-details [id]
  (rpc/GET (str "/executions/" id)))

(defn fetch-execution-workflow-details [id]
  (rpc/GET (str "/executions/workflows/" id)))

(defn abort-execution [id]
  (rpc/GET (str "/executions/abort/" id)))










