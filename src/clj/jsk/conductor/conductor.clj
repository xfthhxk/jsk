(ns jsk.conductor.conductor
  "Coordination of workflows."
  (:require
            [jsk.conductor.quartz :as quartz]
            [jsk.conductor.execution-model :as exm]
            [jsk.conductor.execution-setup :as exs]
            [jsk.conductor.conductor-state :as state]
            [jsk.common.workflow :as w]
            [jsk.common.messaging :as msg]
            [jsk.common.notification :as notify]
            [jsk.common.db :as db]
            [jsk.common.data :as data]
            [jsk.common.conf :as conf]
            [jsk.common.util :as util]
            [jsk.common.graph :as g]
            [jsk.common.job :as j]
            [jsk.conductor.agent-tracker :as track]
            [jsk.conductor.cache :as cache]
            [clojure.string :as string]
            [clojure.set :as set]
            [clojure.core.async :refer [chan go-loop put! <!]]
            [taoensso.timbre :as log]))

(declare start-workflow-execution)

;-----------------------------------------------------------------------
; exec-tbl tracks executions and the status of each job in the workflow.
; It is also used to determine what job(s) to trigger next.
; {execution-id :info IExecutionInfo
;               :running-jobs-count 1
;               :failed? false}
;
;-----------------------------------------------------------------------
; todo: turn in to defonce
; wrap this stuff in delay, they are executed when the file is required!
(defonce ^:private app-state (atom nil))

(defonce ^:private quartz-chan (chan))

(defonce ^:private publish-chan (chan))

(defn- publish [topic data]
  (put! publish-chan {:topic topic :data data}))

(defn- publish-to-agent [agent-name data]
  (publish (str "/jsk/" agent-name) data))

(defn- publish-event [data]
  (publish msg/status-updates-topic data))

(defn- register-agent!
  "Registers the agent in the conductor state"
  [agent-id]
  (swap! app-state #(state/register-agent %1 agent-id (util/now-ms))))

(defn- rm-agents!
  "Removes agents from the conductor's state."
  [agent-ids]
  (swap! app-state #(state/rm-agents %1 agent-ids)))

(defn- put-execution-model!
  "Puts the execution model in the stateful atom."
  [execution-id exec-model]
  (swap! app-state #(state/put-execution-model %1 execution-id exec-model)))

(defn- assert-state []
  (state/assert-state @app-state))

(defn- rm-execution!
  "Removes the execution model identified by execution-id."
  [execution-id]
  (swap! app-state #(state/rm-execution-model %1 execution-id))
  (assert-state))

(defn- mark-jobs-pending!
  [execution-id vertex-agent-map ts]
  (swap! app-state #(state/mark-jobs-pending %1 execution-id vertex-agent-map ts))
  ;(log/debug "After mark-job-pending!" (state/execution-model @app-state execution-id))
  (assert-state))

(defn- mark-job-started!
  [execution-id vertex-id agent-id ts]
  (swap! app-state #(state/mark-job-started %1 execution-id vertex-id agent-id ts))
  ;(log/debug "After mark-job-started!" (state/execution-model @app-state execution-id))
  (assert-state))

(defn- mark-job-paused!
  [execution-id vertex-id agent-id ts]
  (swap! app-state #(state/mark-job-paused %1 execution-id vertex-id agent-id ts))
  ;(log/debug "After mark-job-started!" (state/execution-model @app-state execution-id))
  (assert-state))


(defn- mark-job-finished!
  [execution-id vertex-id agent-id status-id ts]
  (swap! app-state #(state/mark-job-finished %1 execution-id vertex-id agent-id status-id ts))
  ;(log/debug "After mark-job-finished!" (state/execution-model @app-state execution-id))
  (assert-state))

(defn- mark-as-runnable!
  "vertex-ids is a set of ints representing the vertex ids to be run next.
   The vertex ids are both jobs and workflows."
  [execution-id vertex-ids]
  (swap! app-state #(state/mark-as-runnable %1 execution-id vertex-ids))
  (assert-state))

(defn- unmark-as-runnable!
  "next-nodes-set is a set of ints representing the vertex ids to be run next.
   The vertex ids are both jobs and workflows."
  [execution-id vertex-ids]
  (swap! app-state #(state/unmark-as-runnable %1 execution-id vertex-ids))
  (assert-state))

(defn- mark-exec-wf-failed!
  "Marks the exec-wf-id in exec-id as failed."
  [execution-id exec-wf-id]
  (swap! app-state #(state/mark-exec-wf-failed %1 execution-id exec-wf-id))
  ;(log/debug "After mark-exec-wf-failed!" (state/execution-model @app-state execution-id))
  (assert-state))

(defn- ensure-execution-available
  [execution-id]
  (when-not (state/execution-exists? @app-state execution-id)
    (log/info "Loading into memory execution with id" execution-id)
    (let [node-cache (state/node-schedule-cache @app-state)
          {:keys [model]} (exs/resume-workflow-execution-data execution-id node-cache)]
      (put-execution-model! execution-id model))))

(defn- exec-wf-success?
  "Answers if the exec-wf failed"
  [exec-id exec-wf-id]
  (-> @app-state (state/execution-model exec-id) (exm/failed-exec-wf? exec-wf-id) not))

; FIXME: timeout should just be an attr of job
(defn- make-job-commands
  "Returns a sequence of maps for each job to be executed by a remote agent."
  [model vertex-ids exec-wf-id exec-id]
  (let [dc (-> @app-state state/node-schedule-cache) ; data-cache
        run-data {:msg :run-job :execution-id exec-id :exec-wf-id exec-wf-id :trigger-src :conductor :start-ts (util/now)}
        run-fn (fn[v-id]
                 (let [{:keys [node-id agent-name]} (exm/vertex-attrs model v-id)
                       job (cache/job dc node-id)]
                   (merge run-data {:agent-name agent-name :exec-vertex-id v-id :job (assoc job :timeout Integer/MAX_VALUE)})))]
    ;(log/debug "The data cache is: "(with-out-str (clojure.pprint/pprint dc)))
    (map run-fn vertex-ids)))

(defn- run-jobs
  "Determines the agents and the job details for each vertex-id.  Sends one job to
   one agent when an agent is found for the job; otherwise, marks the job as failed."
  [vertex-ids exec-wf-id exec-id]
  (let [job-cmds (-> @app-state
                     (state/execution-model exec-id)
                     (make-job-commands vertex-ids exec-wf-id exec-id))
        job-agent-map (reduce (fn[ans {:keys[agent-name exec-vertex-id]}]
                                (assoc ans exec-vertex-id agent-name))
                              {}
                              job-cmds)]

    ; mark all the jobs as pending
    (mark-jobs-pending! exec-id job-agent-map (util/now-ms))

    (doseq [{:keys [agent-name] :as cmd} job-cmds]
      (log/info "Sending job command: " cmd)
      (assert agent-name "nil agent-name")
      (publish-to-agent agent-name cmd))))



;-----------------------------------------------------------------------
; Runs all workflows with the execution-id specified.
;-----------------------------------------------------------------------
(defn- run-workflows
  "Runs all the workflows specified by exec-wf-ids for exec-id."
  [exec-wf-ids exec-id]
  (let [model (state/execution-model @app-state exec-id)]
    (doseq [exec-vertex-id exec-wf-ids
            :let [exec-wf-id (:exec-wf-to-run (exm/vertex-attrs model exec-vertex-id))]]
      (start-workflow-execution exec-vertex-id exec-wf-id exec-id))))

;-----------------------------------------------------------------------
; Runs all nodes handling jobs and workflows slightly differently.
; 
; TODO: should check to see if the execution is aborting, if so not do anything
;-----------------------------------------------------------------------
(defn- run-nodes
  "Fires off each vertex in vertex-ids.  execution-id is required to figure out
   the exec-vertex-id which serves as the unique id for writing log files to."
  [vertex-ids exec-id]
  (let [model (state/execution-model @app-state exec-id)
        [job-ids wf-ids] (exm/partition-by-node-type model vertex-ids)
        wf-id (exm/single-workflow-context-for-vertices model vertex-ids)]

    (assert (or (seq job-ids) (seq wf-ids))
            (str "Nothing to run for execution " exec-id ", vertices: " vertex-ids))

    (run-jobs job-ids wf-id exec-id)
    (run-workflows wf-ids exec-id)))


;-----------------------------------------------------------------------
; Start a workflow from scratch or within the execution.
;-----------------------------------------------------------------------
(defn- start-workflow-execution
  ([wf-id]
    (let [wf-name (state/node-name @app-state wf-id)
          node-cache (state/node-schedule-cache @app-state)
          {:keys[execution-id model]} (exs/setup-execution wf-id wf-name node-cache)]

      (put-execution-model! execution-id model)

      (publish-event {:execution-event :execution-started
                      :execution-id execution-id
                      :start-ts (exm/start-time model)
                      :node-id wf-id
                      :node-name wf-name
                      :node-type-id data/workflow-type-id})

      ; pass in nil for exec-vertex-id since the actual workflow is represented
      ; by the execution and is not a vertex in itself
      (start-workflow-execution nil (exm/root-workflow model) execution-id)))

  ([exec-vertex-id exec-wf-id exec-id]
   (let [model (state/execution-model @app-state exec-id)
         roots (-> model (exm/workflow-graph exec-wf-id) g/roots)
         ts (util/now)]

     (db/workflow-started exec-wf-id ts)


     (if exec-vertex-id
       (db/execution-vertex-started exec-vertex-id ts)) 

     (publish-event {:execution-event :wf-started
                     :exec-vertex-id exec-vertex-id
                     :exec-wf-id exec-wf-id
                     :start-ts ts
                     :execution-id exec-id})
     (run-nodes roots exec-id))))

;-----------------------------------------------------------------------
; Creates a synthetic workflow to run the specified job in.
;-----------------------------------------------------------------------
(defn- run-job-as-synthetic-wf
  "Runs the job in the context of a synthetic workflow."
  [job-id]
  (let [node-cache (state/node-schedule-cache @app-state)
        {:keys[execution-id model]} (exs/setup-synthetic-execution job-id node-cache)
        start-ts (util/now)
        job-nm (state/node-name @app-state job-id)]

    (db/workflow-started (exm/root-workflow model) start-ts)
    (put-execution-model! execution-id model)

    (publish-event {:execution-event :execution-started
                    :execution-id execution-id
                    :start-ts (exm/start-time model)
                    :node-id job-id
                    :node-name job-nm
                    :node-type-id data/job-type-id})

    (run-nodes (exm/vertices model) execution-id)))

; normalize the run-job as wf thing via as-workflow
(defn- trigger-node-execution
  "Triggers execution of the job or workflow represented by the node-id"
  [node-id]
  (if (-> @app-state (state/node-type-id node-id) util/workflow-type?)
    (start-workflow-execution node-id)
    (run-job-as-synthetic-wf node-id)))


(defn- execution-finished
  "Marks the execution as finished and removes the execution id from app-state."
  [exec-id success? last-exec-wf-id]
  (let [data-cache (state/node-schedule-cache @app-state)
        model (state/execution-model @app-state exec-id)
        root-wf-id (exm/root-workflow model)
        triggered-node-name (exm/triggered-node-name model)
        triggered-node-id (exm/triggered-node-id model)
        triggered-node-type-id (exm/triggered-node-type-id model)
        all-alert-ids (exm/execution-alerts model)
        alerts (cache/alerts-for-status data-cache all-alert-ids success?)
        success-text (if success? "SUCCEEDED" "FAILED")
        ts (util/now)]

    (db/workflow-finished root-wf-id success? ts)
    (db/execution-finished exec-id success? ts)

    ;; pause job 
    (when (not success?)
      ;; pause in quartz
      (quartz/pause-job triggered-node-id)
      ;; send notification to console & UIs that this is now paused
      ;; disable job right now in the database, just in case this
      ;; crashes and console is not avail
      (db/set-node-enabled triggered-node-id false))

    (publish-event {:execution-event :execution-finished
                    :execution-id exec-id
                    :success? success?
                    :status-id (if success? data/finished-success data/finished-error)
                    :finish-ts ts
                    :start-ts (exm/start-time model)
                    :node-id triggered-node-id
                    :node-type-id triggered-node-type-id
                    :node-name triggered-node-name})

    (doseq [{:keys [recipients subject body]} alerts
            :let [subject' (str "Workflow " success-text ": " triggered-node-name)
                  body' (str "Execution ID: " exec-id "\n\n" body)]]
      (notify/enqueue-mail recipients subject' body')))

    (rm-execution! exec-id))

(defn- parents-to-upd
  "Parents to update"
  [vertex-id execution-id success?]
  (loop [v-id vertex-id ans {}]
    (let [{:keys[parent-vertex on-success on-failure belongs-to-wf]}
          (exm/vertex-attrs (state/execution-model @app-state execution-id) v-id)
          deps (if success? on-success on-failure)
          running-count (state/active-jobs-count @app-state execution-id belongs-to-wf)]
      (if (and parent-vertex (empty? deps) (zero? running-count))
        (recur parent-vertex (assoc ans parent-vertex belongs-to-wf))
        ans))))

(defn- mark-wf-and-parent-wfs-finished
  "Finds all parent workflows which also need to be marked as completed.
  NB exec-vertex-id can be nil if the workflow that finished is the root wf"
  [exec-vertex-id exec-wf-id execution-id success?]
  (let [ts (util/now)
        vertices-wf-map (parents-to-upd exec-vertex-id execution-id success?)
        vertices (keys vertices-wf-map)
        wfs (conj (vals vertices-wf-map) exec-wf-id)]

    (log/info "Marking finished for execution-id:" execution-id ", Vertices:" vertices ", wfs:" wfs)

    (when (seq vertices)
      (db/workflows-and-vertices-finished vertices wfs success? ts))

    (publish-event {:execution-event :wf-finished
                    :execution-id execution-id
                    :execution-vertices (conj vertices exec-vertex-id)
                    :finish-ts ts
                    :success? success?})))


(defn- when-wf-finished [execution-id exec-wf-id exec-vertex-id]
  (let [wf-success? (exec-wf-success? execution-id exec-wf-id)
        tbl (state/execution-model @app-state execution-id)
        parent-vertex-id (exm/parent-vertex tbl exec-vertex-id)
        next-nodes (state/successor-nodes @app-state execution-id parent-vertex-id wf-success?)
        exec-failed? (and (not wf-success?) (empty? next-nodes))
        exec-success? (and wf-success? (empty? next-nodes))]

    (log/debug "execution-id " execution-id ", exec-wf-id" exec-wf-id ", exec-vertex-id" exec-vertex-id)
    (log/debug "wf-success?" wf-success? " next-nodes" next-nodes "exec-failed?" exec-failed? "exec-success?" exec-success?)


    (mark-wf-and-parent-wfs-finished exec-vertex-id exec-wf-id execution-id wf-success?)

    (when (seq next-nodes)
      (run-nodes next-nodes execution-id))

    ; execution finished?
    (if (or exec-failed? exec-success?)
      (execution-finished execution-id exec-success? exec-wf-id))))

(defn- when-job-started
  "Logs the status to the db and updates the app-state"
  [{:keys[execution-id exec-vertex-id agent-id] :as data}]
  (let [start-ts (util/now)]
    (db/execution-vertex-started exec-vertex-id start-ts)
    (mark-job-started! execution-id exec-vertex-id agent-id (util/now-ms))
    (publish-event (merge {:execution-event :job-started :start-ts start-ts :status data/started-status}
                          (select-keys data [:execution-id :exec-vertex-id :exec-wf-id])))))


;; publish event to notify the UIs
;; send alerts if any
(defn- notify-job-finished [{:keys[execution-id exec-vertex-id agent-id exec-wf-id error-msg] :as msg} status-id success? fin-ts]
  (let [model (state/execution-model @app-state execution-id)
        data-cache (state/node-schedule-cache @app-state)
        job-name (exm/vertex-name model exec-vertex-id)
        all-alert-ids (exm/associated-alerts model exec-vertex-id)
        alerts (cache/alerts-for-status data-cache all-alert-ids success?)
        success-text (if success? "SUCCEEDED" "FAILED")]

    (publish-event (-> msg
                       (select-keys [:execution-id :exec-vertex-id :success? :error-msg])
                       (merge {:finish-ts fin-ts :status status-id :execution-event :job-finished})))

    (doseq [{:keys [recipients subject body]} alerts
            :let [subject' (str "JOB " success-text ": " job-name)
                  body' (str error-msg "\n\nExecution ID: " execution-id ", Instance ID: " exec-vertex-id "\n\n" body)]]
      (notify/enqueue-mail recipients subject' body'))))


; Make idempotent, so if agent sends this again we don't blow up.
(defn- when-job-ended
  "Decrements the running job count for the execution id.
   Determines next set of jobs to run.
   Determines if the workflow is finished and/or errored.
   Sends job-finished-ack to agent.
   publish for distribution.
   Set msg-ack-kw if there is no agent to inform. if msg-ack-kw is nil agent won't be sent an ack."
  [{:keys[execution-id exec-vertex-id agent-id exec-wf-id error-msg] :as msg} msg-ack-kw status-id success?]
  (log/debug "job-ended: " msg)

  ; update status in db
  (let [fin-ts (util/now)]

    (db/execution-vertex-finished exec-vertex-id status-id fin-ts)

    ; update status memory and ack the agent so it can clear it's memory
    (when msg-ack-kw
      (publish-to-agent agent-id (-> msg (select-keys [:execution-id :exec-vertex-id]) (assoc :msg msg-ack-kw))))

    (let [; execution-active-count (state/active-jobs-count @app-state execution-id)
          next-nodes (state/successor-nodes @app-state execution-id exec-vertex-id success?)
          exec-wf-fail? (and (not success?) (empty? next-nodes))]

      
      ;; (log/info "execution-model for " execution-id "\n"
      ;;           (with-out-str (clojure.pprint/pprint (state/execution-model @app-state execution-id))))

      ;; (log/info "next-nodes" next-nodes)

      ;; next-nodes is a set of ints identifying the exec-vertex-ids
      ;; mark vertices as runnable before marking job as finished for
      ;; other threads looking at the state so they don't prematurely
      ;; mark the workflow as finished
      (when (seq next-nodes)
        (mark-as-runnable! execution-id next-nodes))

      (mark-job-finished! execution-id exec-vertex-id agent-id status-id fin-ts)

      ;; FIXME: check that nothing else is currently running or has
      ;; potential to run
      (let [runnables? (state/runnable-vertices? @app-state execution-id)
            wf-active-count (state/active-jobs-count @app-state execution-id exec-wf-id)
            exec-wf-finished? (and (zero? wf-active-count) (empty? next-nodes))]

        (notify-job-finished msg status-id success? fin-ts)

        (log/debug "after-job-ended: wf-active-count " wf-active-count ", next-nodes" next-nodes ", exec-wf-fail?" exec-wf-fail? ", exec-wf-finished?" exec-wf-finished?)

        (if exec-wf-fail?
          (mark-exec-wf-failed! execution-id exec-wf-id))

        (when exec-wf-finished?
          (when-wf-finished execution-id exec-wf-id exec-vertex-id))

        (when (and (not exec-wf-finished?) (seq next-nodes))
          (run-nodes next-nodes execution-id))

        ;; run nodes and unmark the things which we marked as runnable above
        (when (seq next-nodes)
          (unmark-as-runnable! execution-id next-nodes))))))

(defn- when-job-resumed
  "For resumed job."
  [execution-id exec-vertex-id agent-id]
  (let [ts (util/now)]
    ;; save the status to the database
    (db/execution-vertex-finished exec-vertex-id data/started-status ts)
    (mark-job-started! execution-id exec-vertex-id agent-id ts)
    (publish-to-agent agent-id {:execution-id execution-id :exec-vertex-id exec-vertex-id :msg :job-resumed-ack})
    ;; notify the UIs
    (publish-event {:status data/started-status :execution-event :job-resumed :execution-id execution-id :exec-vertex-id exec-vertex-id :start-ts ts})))

(defn- when-job-paused
  "For paused job."
  [execution-id exec-vertex-id agent-id]
  (let [ts (util/now)]
    ;; save the status to the database
    (db/execution-vertex-finished exec-vertex-id data/paused-status ts)
    (mark-job-paused! execution-id exec-vertex-id agent-id ts)
    (publish-to-agent agent-id {:execution-id execution-id :exec-vertex-id exec-vertex-id :msg :job-paused-ack})
    ;; notify the UIs
    (publish-event {:status data/paused-status :execution-event :job-paused :execution-id execution-id :exec-vertex-id exec-vertex-id :finished-ts ts})))

;-----------------------------------------------------------------------
; -- when job pause requested
;-----------------------------------------------------------------------
(defn- when-pause-job-requested
  "Sends the agent running the job a pause job message if the execution exists."
  [execution-id exec-vertex-id]
  (when-let [model (state/execution-model @app-state execution-id)]
    (let [{:keys [agent-name status]} (exm/vertex-attrs model exec-vertex-id)]
      (publish-to-agent agent-name {:execution-id execution-id :exec-vertex-id exec-vertex-id :msg :pause-job}))))

;-----------------------------------------------------------------------
; -- when job resume requested
;-----------------------------------------------------------------------
(defn- when-resume-job-requested
  "Sends the agent running the job a resume job message if the execution exists
   and the exec-vertex-id is marked as paused."
  [execution-id exec-vertex-id]
  (when-let [model (state/execution-model @app-state execution-id)]
    (let [{:keys [agent-name status]} (exm/vertex-attrs model exec-vertex-id)]
      (when (= data/paused-status status)
        (publish-to-agent agent-name {:execution-id execution-id :exec-vertex-id exec-vertex-id :msg :resume-job})))))


;-----------------------------------------------------------------------
; -- when job abort requested
;-----------------------------------------------------------------------
(defn- when-abort-job-requested
  "Sends the agent running the job an abort job message if the execution exists
   and the exec-vertex-id is marked as started"
  [execution-id exec-vertex-id]
  (when-let [model (state/execution-model @app-state execution-id)]
    (let [{:keys [agent-name status]} (exm/vertex-attrs model exec-vertex-id)]
      (when (= data/started-status status)
        (publish-to-agent agent-name {:execution-id execution-id :exec-vertex-id exec-vertex-id :msg :abort-job})))))

(defn- when-execution-action-requested
  "Takes the execution-id and a fn which accepts execution-id and a execution-vertex-id and calls
   the fn for each vertex within the execution-id."
  [action-fn execution-id]
  (when-let [model (state/execution-model @app-state execution-id)]
    (doseq [id (exm/job-vertices model)]
      (action-fn execution-id id))))

(def when-abort-execution-requested (partial when-execution-action-requested when-abort-job-requested))
(def when-pause-execution-requested (partial when-execution-action-requested when-pause-job-requested))
(def when-resume-execution-requested (partial when-execution-action-requested when-resume-job-requested))

; If the execution is already loaded, then just do run-nodes,
; otherwise load execution and then do run-nodes
(defn- when-restart-job-requested
  "restart job"
  [execution-id exec-vertex-id]
  (ensure-execution-available execution-id)
  (run-nodes [exec-vertex-id] execution-id))

  
(defn- when-force-success
  "Force success on an execution vertex for a non-success node"
  [execution-id exec-vertex-id]
  (log/info "Force success exec-vertex-id " exec-vertex-id " for execution " execution-id)
  (ensure-execution-available execution-id)
  (let [model (state/execution-model @app-state execution-id)
        exec-wf-id (exm/owning-execution-workflow-id model exec-vertex-id)]
  (when-job-ended {:execution-id execution-id :exec-vertex-id exec-vertex-id :exec-wf-id exec-wf-id}
                  nil ;; this is the msg-ack-kw, nil to not send agent an ack
                  data/forced-success
                  true)))


;-----------------------------------------------------------------------
; -- Networked agents --
;-----------------------------------------------------------------------


(defn destroy []
  (quartz/stop))


;-----------------------------------------------------------------------
; Dispatch for messages received from the sub socket.
;-----------------------------------------------------------------------
(defmulti dispatch :msg)

;-----------------------------------------------------------------------
; Add the agent and acknowledge.
;-----------------------------------------------------------------------
(defmethod dispatch :agent-registering [{:keys[agent-id]}]
  (log/info "Agent registering: " agent-id)
  (register-agent! agent-id)
  (publish-to-agent agent-id {:msg :agent-registered}))

;-----------------------------------------------------------------------
; If we know about the agent then, update the last heartbeat.
; Otherwise ask the agent to register. Agent could have registered,
; switch fails, heartbeats missed, conductor marks it as dead, remvoes
; from tracker, switch fixed, agent responds to heartbeat.
;-----------------------------------------------------------------------
(defmethod dispatch :heartbeat-ack [{:keys[agent-id]}]
  (if (-> @app-state state/agent-tracker (track/agent-exists? agent-id))
    (swap! app-state #(state/heartbeat-rcvd %1 agent-id (util/now-ms)))
    (publish-to-agent agent-id {:msg :agents-register})))

;-----------------------------------------------------------------------
; Agent received the :run-job request, do what's required when a job
; is started.
;-----------------------------------------------------------------------
(defmethod dispatch :run-job-ack [data]
  (log/info "Run job ack:" data)
  (when-job-started data))


;-----------------------------------------------------------------------
; Agent says the job is finished.
;-----------------------------------------------------------------------
(defmethod dispatch :job-finished [{:keys [success? execution-id] :as msg}]
  (let [status (if success? data/finished-success data/finished-error)]
    (when-job-ended msg :job-finished-ack status success?)))


; TODO: send an ack
(defmethod dispatch :node-save [{:keys[node-id node-type-id] :as msg}]
  (log/debug "Node save for node: " msg)
  (let [{:keys [is-enabled] :as n}  (db/get-node-by-id node-id node-type-id)
        quartz-fn (if is-enabled quartz/resume-job quartz/pause-job)]
    (log/debug "node from db is " n)
    (swap! app-state #(state/save-node %1 n))
    (quartz-fn node-id)))


(defmethod dispatch :alert-save [{:keys [alert-id]}]
  (log/info "reload alert " alert-id)
  (let [a (db/get-alert alert-id)]
    (swap! app-state #(state/save-node %1 a))))

; TODO: send an ack,
; Cron expr could have changed, so update the triggers.
; Though you can check to see if they're different before you do.
(defmethod dispatch :schedule-save [{:keys[schedule-id]}]
  (log/debug "schedule save for schedule-id: " schedule-id)
  (let [{:keys[cron-expression] :as s} (db/get-schedule schedule-id)
        find-assocs #(-> @app-state
                         state/node-schedule-cache
                         (cache/schedule-assocs-for-schedule schedule-id))]

    (swap! app-state #(state/save-schedule %1 s)) 

    (doseq [{:keys[node-schedule-id node-id]} (find-assocs)]
      (quartz/schedule-cron-job! node-schedule-id node-id cron-expression))))

; TODO: send an ack
; Remove existing assoc from the cache
; add the new ones
; schedule new ones w/ quartz
(defmethod dispatch :schedule-assoc [{:keys[node-id]}]
  (log/debug "schedule assoc save for node-id: " node-id)
  (let [orig-assoc-ids (-> @app-state state/node-schedule-cache (cache/schedule-assoc-ids-for-node node-id))
        new-assocs (db/node-schedules-for-node node-id)
        c (-> app-state
              (swap! #(state/replace-schedule-assocs %1 node-id new-assocs))
              state/node-schedule-cache)]

    (quartz/rm-triggers! orig-assoc-ids)

    (doseq [{:keys[node-schedule-id cron-expression]} (cache/schedule-assocs-with-cron-expr-for-node c node-id)]
      (quartz/schedule-cron-job! node-schedule-id node-id cron-expression))))

;-----------------------------------------------------------------------
; Abort job/executions
;-----------------------------------------------------------------------

(defmethod dispatch :abort-job-ack [data]
  (log/info "Abort job ack:" data))

(defmethod dispatch :pause-job-ack [data]
  (log/info "Pause job ack:" data))

(defmethod dispatch :resume-job-ack [data]
  (log/info "Resume job ack:" data))

(defmethod dispatch :job-aborted [{:keys [execution-id exec-vertex-id] :as msg}]
  (when-let [model (state/execution-model @app-state execution-id)]
    (let [{:keys [belongs-to-wf]} (exm/vertex-attrs model exec-vertex-id)
          msg' (assoc msg :exec-wf-id belongs-to-wf)]
      (when-job-ended msg' :job-aborted-ack data/aborted-status false))))


(defmethod dispatch :job-paused [{:keys [execution-id exec-vertex-id agent-id] :as msg}]
  (when-let [model (state/execution-model @app-state execution-id)]
    (when-job-paused execution-id exec-vertex-id agent-id)))

(defmethod dispatch :job-resumed [{:keys [execution-id exec-vertex-id agent-id] :as msg}]
  (when-let [model (state/execution-model @app-state execution-id)]
    (when-job-resumed execution-id exec-vertex-id agent-id)))

; Comes from the console via user action for example
(defmethod dispatch :request-job-abort [{:keys [execution-id exec-vertex-id]}]
  (log/info "Aborting job" exec-vertex-id "within execution" execution-id)
  (when-abort-job-requested execution-id exec-vertex-id))

(defmethod dispatch :request-execution-abort [{:keys [execution-id]}]
  (log/info "Aborting execution" execution-id)
  (when-abort-execution-requested execution-id))

(defmethod dispatch :request-execution-pause [{:keys [execution-id]}]
  (log/info "Pausing execution" execution-id)
  (when-pause-execution-requested execution-id))

(defmethod dispatch :request-execution-resume [{:keys [execution-id]}]
  (log/info "Resuming execution" execution-id)
  (when-resume-execution-requested execution-id))

;-----------------------------------------------------------------------
; Restart job
;-----------------------------------------------------------------------
(defmethod dispatch :request-job-restart [{:keys [execution-id exec-vertex-id]}]
  (log/info "Restart job" exec-vertex-id "in execution" execution-id)
  (when-restart-job-requested execution-id exec-vertex-id))

;-----------------------------------------------------------------------
; Resume job
;-----------------------------------------------------------------------
(defmethod dispatch :request-job-resume [{:keys [execution-id exec-vertex-id]}]
  (log/info "Resume job" exec-vertex-id "in execution" execution-id)
  (when-resume-job-requested execution-id exec-vertex-id))

;-----------------------------------------------------------------------
; Pause job
;-----------------------------------------------------------------------
(defmethod dispatch :request-job-pause [{:keys [execution-id exec-vertex-id]}]
  (log/info "Pause job" exec-vertex-id "in execution" execution-id)
  (when-pause-job-requested execution-id exec-vertex-id))

;-----------------------------------------------------------------------
; Force success job
;-----------------------------------------------------------------------
(defmethod dispatch :request-force-success [{:keys [execution-id exec-vertex-id]}]
  (log/info "Force success" exec-vertex-id "in execution" execution-id)
  (when-force-success execution-id exec-vertex-id))


(defmethod dispatch :ping [{:keys[reply-to] :as data}]
  (publish reply-to {:msg :pong}))

(defmethod dispatch :trigger-node [{:keys[node-id]}]
  (log/info "trigger-node-execution for " node-id)
  (trigger-node-execution node-id))


;-----------------------------------------------------------------------
; This gets called if we don't have a handler setup for a msg type
;-----------------------------------------------------------------------
(defmethod dispatch :default [data]
  (log/warn "No method to handle data: " data))


;-----------------------------------------------------------------------
; Find dead agents, and mark their jobs as unknown status.
;-----------------------------------------------------------------------
(defn- run-dead-agent-check
  "Checks for dead agents based on last heartbeat received.
   Marks those agents who last sent heartbeats before now - interval-ts as dead.
   Removes dead agents so they can't be used for running jobs.
   Marks affected execution-vertices as in unknown state, but does not remove
   from exec-infos, in hope that when agent connects again it will update us.
   If agent doesn't know anything, timeout will have to kick in and fail the job.
   Email users about agent disconnect and affected exec-vertex ids"
  [interval-ms]
  (let [ts-threshold (- (util/now-ms) interval-ms)
        tracker (state/agent-tracker @app-state)
        dead-agents (track/dead-agents tracker ts-threshold)
        agent-job-map (track/dead-agents-job-map tracker ts-threshold)
        vertex-ids (-> agent-job-map vals set)]

    (when (seq dead-agents)
      (log/info "Dead agent check, dead agents:" dead-agents ", affected vertex-ids:" vertex-ids)

      ; mark status as unknown in db
      (db/update-execution-vertices-status vertex-ids data/unknown-status (util/now-ms))
      ; TODO: publish unknown-status for jobs/wfs to UIs

      ; remove from tracker
      (swap! app-state #(state/rm-agents %1 dead-agents))

      (notify/dead-agents dead-agents vertex-ids))))


(defn- ensure-agents-connected
  "Ensures all agents defined in the system are connected. Otherwise,
   ask agents to register."
  []
  (let [cn-fn #(->> @app-state state/agent-tracker track/agents set)
        all-agents (->> @app-state state/node-schedule-cache cache/agents (map :agent-name) set)]
    (loop [connected (cn-fn)]
      (let [not-connected (set/difference all-agents connected)]
        (when (seq not-connected)
          (log/info "Following agents have not yet connected: " not-connected)
          (put! publish-chan {:topic msg/broadcast-topic :data {:msg :agents-register}})
          (Thread/sleep 1000)
          (recur (cn-fn)))))
    (log/info "All agents connected.")))


(defn- populate-cache
  "Loads all agents, jobs, workflows, schedules and associations from the db."
  []
  (let [c (-> (cache/new-cache)
              (cache/put-agents (db/ls-agents))
              (cache/put-alerts (db/ls-alerts))
              (cache/put-jobs (db/ls-jobs))
              (cache/put-workflows (db/ls-workflows))
              (cache/put-schedules (db/ls-schedules))
              (cache/put-schedule-assocs (db/ls-node-schedules)))]
    (swap! app-state #(state/set-node-schedule-cache %1 c))))

(defn- populate-quartz-triggers
  "Populates quartz triggers by reading from the cache. Only for enabled jobs/workflows."
  []
  (let [nsc (-> @app-state state/node-schedule-cache)]
    (doseq [{:keys[node-schedule-id node-id cron-expression]} (cache/schedule-assocs-with-cron-expr nsc)]
      (quartz/schedule-cron-job! node-schedule-id node-id cron-expression))))


(defn init [pub-port sub-port]

  (reset! app-state (state/new-state))
  
  (log/info "Connecting to database.")
  (conf/init-db)

  (notify/init)

  (let [host "*"
        bind? true]
   (msg/relay-reads "request-processor" host sub-port bind? msg/all-topics dispatch)
   (msg/relay-writes publish-chan host pub-port bind?))

  ;----------------------------------------------------------------------
  ; REVIEW
  ; for some reason if heartbeats are started first things work otherwise not
  ;----------------------------------------------------------------------
  (let [hb-ms (conf/heartbeats-interval-ms)
        dead-after-ms (conf/heartbeats-dead-after-ms)]
    (util/periodically "heartbeats" hb-ms #(publish msg/broadcast-topic {:msg :heartbeat}))
    (util/periodically "dead-agent-checker" dead-after-ms (partial run-dead-agent-check dead-after-ms)))

  (log/info "Populating cache.")
  (populate-cache)

  (ensure-agents-connected)


  (log/info "Initializing Quartz.")
  (quartz/init quartz-chan)
  ; quartz puts stuff on the quartz-chan when a trigger is fired
  (msg/process-read-channel quartz-chan #(-> :node-id %1 trigger-node-execution))


  (log/info "Populating Quartz.")
  (populate-quartz-triggers)


  (log/info "Starting Quartz.")
  (quartz/start)
  (log/info "Conductor started successfully."))
