;----------------------------------------------------------------------
; Async remote function calls.  Uses rpc namespace.
; These all return a core.async channel.
;----------------------------------------------------------------------
(ns jsk.rfn
  (:require [jsk.rpc  :as rpc]
            [jsk.util :as u]))

;----------------------------------------------------------------------
; Job & Workflow functions
;----------------------------------------------------------------------
(defn fetch-all-nodes []
  (rpc/GET "/nodes"))

(defn fetch-node-info [node-id]
  (rpc/GET (str "/nodes/" node-id)))


(defn fetch-schedule-associations [node-id]
  (rpc/GET (str "/nodes/schedules/" node-id)))


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


;----------------------------------------------------------------------
; Schedule functions
;----------------------------------------------------------------------
(defn fetch-all-schedules []
  (rpc/GET "/schedules"))

(defn fetch-schedule-details [schedule-id]
  (rpc/GET (str "/schedules/" schedule-id)))

(defn save-schedule [data]
  (rpc/POST "/schedules/save" data))

(defn save-schedule-associations [data]
  (rpc/POST "/schedules/assoc" data))

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
  (rpc/GET (str "/workflows/trigger-now/" wf-id)))


;----------------------------------------------------------------------
; Execution functions
;----------------------------------------------------------------------
(defn fetch-execution-details [id]
  (rpc/GET (str "/executions/" id)))

(defn fetch-execution-workflow-details [id]
  (rpc/GET (str "/executions/workflows/" id)))

(defn abort-execution [id]
  (rpc/GET (str "/executions/abort/" id)))

(defn abort-job [execution-id exec-vertex-id]
  (rpc/GET (str "/executions/abort/" execution-id "/" exec-vertex-id)))

(defn resume-execution [exec-id vertex-id]
  (rpc/GET (str "/executions/resume/" exec-id "/" vertex-id)))

(defn search-executions [data]
  (rpc/POST "/executions/search/q" data))




;----------------------------------------------------------------------
; Agent functions
;----------------------------------------------------------------------
(defn fetch-all-agents []
  (rpc/GET "/agents"))

(defn fetch-agent-details [agent-id]
  (rpc/GET (str "/agents/" agent-id)))

(defn save-agent [data]
  (rpc/POST "/agents/save" data))




;----------------------------------------------------------------------
; Explorer functions
;----------------------------------------------------------------------
(defn fetch-explorer-root []
  (rpc/GET "/explorer"))

(defn fetch-explorer-directory [dir-id]
  (rpc/GET (str "/explorer/directory/" dir-id)))

(defn save-directory [data]
  (rpc/POST "/explorer/directory" data))

(defn rm-directory [id]
  (rpc/DELETE (str "/explorer/directory/" id)))

(defn new-empty-workflow [dir-id]
  (rpc/POST (str "/explorer/directory/" dir-id "/new-empty-workflow") ""))

(defn new-empty-job [dir-id]
  (rpc/POST (str "/explorer/directory/" dir-id "/new-empty-job") ""))

