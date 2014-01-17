(ns jsk.conductor.conductor-state
  (:require [jsk.common.util :as util]
            [jsk.common.data :as data]
            [jsk.conductor.cache :as cache]
            [jsk.conductor.execution-model :as exm]
            [jsk.conductor.agent-tracker :as track]))

(defn new-state []
  {:execution-models {}
   :agent-tracker nil
   :node-schedule-cache nil})

;-----------------------------------------------------------------------
; Execution Model
;-----------------------------------------------------------------------
(defn set-node-schedule-cache [state c]
  (assoc state :node-schedule-cache c))

(defn node-schedule-cache [state]
  (:node-schedule-cache state))

;-----------------------------------------------------------------------
; Agent Tracker
;-----------------------------------------------------------------------
(defn set-agent-tracker [state trk]
  (assoc state :agent-tracker trk))

(defn agent-tracker [state]
  (:agent-tracker state))


;-----------------------------------------------------------------------
; Execution Model
;-----------------------------------------------------------------------
(defn execution-model
  "Returns the execution model for the execution-id"
  [state execution-id]
  (get-in state [:execution-models execution-id]))

(defn execution-exists?
  "Answers if the execution-id is present in the state."
  [state execution-id]
  (-> state (execution-model execution-id) util/present?))

(def execution-not-exists? (complement execution-exists?))

(defn put-execution-model
  "Puts the execution model into the state.
   Asserts the model is new based on execution-id."
  [state execution-id execution-model]
  (assert (execution-not-exists? state execution-id)
          (str "Execution already exists: " execution-id))

  (assoc-in state [:execution-models execution-id] execution-model))


(defn rm-execution-model
  "Removes the execution model identified by execution-id."
  [state execution-id]
  (update-in state [:execution-models] dissoc execution-id))

;-----------------------------------------------------------------------
; Job status and Agent -> Job relationships
;-----------------------------------------------------------------------

(defn- set-status-for-jobs
  "Updates the status for jobs and returns the updated state."
  [state execution-id status-id & vertex-ids]
  (reduce (fn [st v-id]
            (update-in st [:execution-models execution-id] #(exm/update-status %1 status-id v-id)))
          state
          vertex-ids))

(defn mark-jobs-pending
  "Marks the jobs (vertex-ids) as pending. Called before submitting a job to an agent.
   vertex-agent-map is a map of vertex-id to an agent-id."
  [state execution-id vertex-agent-map ts]
  (let [vertex-ids (keys vertex-agent-map)]
    (-> state
        (set-status-for-jobs execution-id data/pending-status vertex-ids)
        agent-tracker
        (track/add-job-agent-assocs vertex-agent-map))))

(defn mark-job-started
  "Marks the job as started.  Called after an agent sends job-started-ack."
  [state execution-id vertex-id agent-id ts]
  (-> state
      (set-status-for-jobs execution-id [vertex-id] data/started-status)))

(defn mark-job-finished
  "Marks the job as finished.  Called after an agent sends job-finished."
  [state execution-id vertex-id agent-id status-id ts]
  (-> state
      (set-status-for-jobs execution-id [vertex-id] status-id)))

(defn count-for-job-status
  "Answers with the jobs count for the execution-id and the status-id."
  [state execution-id exec-wf-id status-id]
  (-> state
      (execution-model execution-id)
      (exm/status-count status-id exec-wf-id)))

(defn mark-exec-wf-failed
  "Marks the exec wf as failed."
  [state execution-id exec-wf-id]
  (update-in state [:execution-models execution-id] #(exm/mark-exec-wf-failed %1 exec-wf-id)))


;-----------------------------------------------------------------------
; Find which things should execute next
;-----------------------------------------------------------------------
(defn successor-nodes
  "Answers with the successor nodes for the execution status of node-id
  belonging to execution-id."
  [state execution-id exec-vertex-id success?]
  (-> state
      (execution-model execution-id)
      (exm/dependencies exec-vertex-id success?)))

(defn node
  "Answers with the node for the node-id"
  [state node-id]
  (-> state node-schedule-cache (cache/node node-id)))

(defn node-name
  "Answers with the name for the node-id"
  [state node-id]
  (-> state (node node-id) :node-name))

(defn node-type-id
  "Answers with the name for the node-id"
  [state node-id]
  (-> state (node node-id) :node-type-id))

(defn agent-count
  "Answers with the number of connected agents."
  [state]
  (-> state agent-tracker track/agents count))

(defn register-agent
  "Registers the agent in the state's agent tracker.
   ts is a timestamp."
  [state agent-id ts]
  (update-in state [:agent-tracker] #(track/add-agent %1 agent-id ts)))

(defn rm-agents
  "Removes from state's agent tracker all agents represented by agent-ids."
  [state agent-ids]
  (update-in state [:agent-tracker] #(track/rm-agents %1 agent-ids)))

(defn heartbeat-rcvd
  "Updates the last hb received timestamp."
  [state agent-id ts]
  (update-in state [:agent-tracker] #(track/agent-heartbeat-rcvd %1 agent-id ts)))


(defn- save-to-cache
  [state f data]
  (update-in state [:node-schedule-cache] #(-> %1 (f data))))

(defn save-node
  "Saves the node. n is a map."
  [state n]
  (save-to-cache state cache/put-node n))

(defn save-schedule
  "Saves the schedule s which is a map."
  [state s]
  (save-to-cache state cache/put-schedule s))

(defn save-assoc
  "Saves the node schedule association."
  [state node-sched]
  (save-to-cache state cache/put-assoc node-sched))

(defn replace-schedule-assocs
  "Replaces any and all existing schedule associations for node-id
   with node-scheds."
  [state node-id node-scheds]
  (update-in state [:node-schedule-cache] #(cache/replace-schedule-assocs %1 node-id node-scheds)))
