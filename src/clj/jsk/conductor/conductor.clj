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

(declare start-workflow-execution when-job-finished)


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

(defn- rm-execution!
  "Removes the execution model identified by execution-id."
  [execution-id]
  (swap! app-state #(state/rm-execution-model %1 execution-id)))

(defn- mark-jobs-pending!
  [execution-id vertex-ids agent-id ts]
  (swap! app-state #(state/mark-jobs-pending %1 execution-id vertex-ids agent-id ts)))

(defn- mark-job-started!
  [execution-id vertex-id agent-id ts]
  (swap! app-state #(state/mark-job-started %1 execution-id vertex-id agent-id ts)))

(defn- mark-job-finished!
  [execution-id vertex-id agent-id status-id ts]
  (swap! app-state #(state/mark-job-finished %1 execution-id vertex-id agent-id status-id ts)))

(defn- mark-exec-wf-failed!
  "Marks the exec-wf-id in exec-id as failed."
  [exec-id exec-wf-id]
  (swap! app-state #(state/mark-exec-wf-failed %1 exec-id exec-wf-id)))

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
                 (let [job-id (-> model (exm/vertex-attrs v-id) :node-id)
                       {:keys [agent-id] :as job} (cache/job dc job-id)
                       agent-name (-> dc (cache/agent agent-id) :agent-name)]
                   (merge run-data {:agent-name agent-name :exec-vertex-id v-id :job (assoc job :timeout Integer/MAX_VALUE)})))]
    (log/debug "The data cache is: "(with-out-str (clojure.pprint/pprint dc)))
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
    (swap! app-state #(state/mark-jobs-pending %1 exec-id job-agent-map (util/now-ms)))

    (doseq [{:keys [agent-name] :as cmd} job-cmds]
      (log/info "Sending job command: " cmd)
      (publish (str "/jsk/" agent-name) cmd))))



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
;-----------------------------------------------------------------------
(defn- run-nodes
  "Fires off each node in node-ids.  execution-id is required to figure out
   the exec-vertex-id which serves as the unique id for writing log files to."
  [node-ids exec-id]
  (let [model (state/execution-model @app-state exec-id)
        [job-ids wf-ids] (exm/partition-by-node-type model node-ids)
        wf-id (exm/single-workflow-context-for-vertices model node-ids)]

    (run-jobs job-ids wf-id exec-id)
    (run-workflows wf-ids exec-id)))


;-----------------------------------------------------------------------
; Start a workflow from scratch or within the execution.
;-----------------------------------------------------------------------
(defn- start-workflow-execution
  ([wf-id]
    (let [wf-name (state/node-name @app-state wf-id)
          {:keys[execution-id model]} (exs/setup-execution wf-id wf-name)]

      (put-execution-model! execution-id model)

      (publish-event {:event :execution-started
                      :execution-id execution-id
                      :start-ts (exm/start-time model)
                      :wf-name wf-name})

      (log/debug "Execution model for execution-id " execution-id)
      (-> model clojure.pprint/pprint with-out-str log/debug)

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

     (publish-event {:event :wf-started
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
  (let [{:keys[execution-id model]} (exs/setup-synthetic-execution job-id)
        start-ts (util/now)
        job-nm (state/node-name @app-state job-id)]

    (log/info "model: " model ", job-nm: " job-nm)
    (db/workflow-started (exm/root-workflow model) start-ts)
    (put-execution-model! execution-id model)
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
  (let [model (state/execution-model @app-state exec-id)
        root-wf-id (exm/root-workflow model)
        exec-name (exm/execution-name model)
        ts (util/now)]

    (db/workflow-finished root-wf-id success? ts)
    (db/execution-finished exec-id success? ts)

    (publish-event {:event :execution-finished
                    :execution-id exec-id
                    :success? success?
                    :status (if success? data/finished-success data/finished-error)
                    :finish-ts ts
                    :start-ts (exm/start-time model)
                    :wf-name exec-name})

    (rm-execution! exec-id)))

(defn- parents-to-upd
  "Parents to update"
  [vertex-id execution-id success?]
  (loop [v-id vertex-id ans {}]
    (let [{:keys[parent-vertex on-success on-failure belongs-to-wf]}
          (exm/vertex-attrs (state/execution-model @app-state execution-id) v-id)
          deps (if success? on-success on-failure)
          running-count (state/count-for-job-status @app-state execution-id belongs-to-wf data/started-status)]
      (if (and parent-vertex (empty? deps) (zero? running-count))
        (recur parent-vertex (assoc ans parent-vertex belongs-to-wf))
        ans))))

(defn- mark-wf-and-parent-wfs-finished
  "Finds all parent workflows which also need to be marked as completed.
  NB exec-vertex-id can be nil if the workflow that finished is the root wf"
  [exec-vertex-id exec-wf-id execution-id success?]
  (let [ts (util/now)
        vertices-wf-map (parents-to-upd exec-vertex-id execution-id success?)
        vertices (if exec-vertex-id
                   (conj (keys vertices-wf-map) exec-vertex-id)
                   [])
        wfs (conj (vals vertices-wf-map) exec-wf-id)]
    (log/info "Marking finished for execution-id:" execution-id ", Vertices:" vertices ", wfs:" wfs)
    (db/workflows-and-vertices-finished vertices wfs success? ts)

    ; FIXME: need to iterate over all the vertices and publish-event
    (if vertices
      (publish-event {:event :wf-finished
                      :execution-id execution-id
                      :execution-vertices vertices
                      :finish-ts ts
                      :success? success?}))))


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
  [{:keys[execution-id exec-wf-id exec-vertex-id agent-id] :as data}]
  
  (db/execution-vertex-started exec-vertex-id (util/now))
  (mark-job-started! execution-id exec-vertex-id agent-id (util/now-ms))
  (publish-event data)) ; FIXME: this doesn't have all the info like the job name, start-ts see old execution.clj


; FIXME: update-running-jobs-count! is updating an atom and so is the code below put in dosync or what not
; Make idempotent, so if agent sends this again we don't blow up.
(defn- when-job-finished
  "Decrements the running job count for the execution id.
   Determines next set of jobs to run.
   Determines if the workflow is finished and/or errored.
   Sends job-finished-ack to agent.
   publish for distribution."
  [{:keys[execution-id exec-vertex-id agent-id exec-wf-id success? forced-by-conductor? error-msg] :as msg}]
  (log/debug "job-finished: " msg)

  ; update status in db
  (let [fin-status (if success? data/finished-success data/finished-error)
        fin-ts (util/now)]

    (db/execution-vertex-finished exec-vertex-id fin-status fin-ts)
    (mark-job-finished! execution-id exec-vertex-id agent-id fin-status fin-ts)

    ; update status memory and ack the agent so it can clear it's memory
    ; agent-id can be null if the conductor is calling this method directly eg when no agent is available
    (when (not forced-by-conductor?)
      (publish agent-id (-> msg (select-keys [:execution-id :exec-vertex-id]) (assoc :msg :job-finished-ack))))

    (let [new-count (state/count-for-job-status @app-state execution-id exec-wf-id data/started-status)
          next-nodes (state/successor-nodes @app-state execution-id exec-vertex-id success?)
          exec-wf-fail? (and (not success?) (empty? next-nodes))
          exec-wf-finished? (and (zero? new-count) (empty? next-nodes))]

      (publish-event (-> msg
                         (select-keys [:execution-id :exec-vertex-id :success? :error-msg])
                         (merge {:finish-ts fin-ts :status fin-status :event :job-finished})))

      (if exec-wf-fail?
        (mark-exec-wf-failed! execution-id exec-wf-id))

      (if exec-wf-finished?
        (when-wf-finished execution-id exec-wf-id exec-vertex-id)
        (run-nodes next-nodes execution-id)))))

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
  (publish agent-id {:msg :agent-registered}))

;-----------------------------------------------------------------------
; If we know about the agent then, update the last heartbeat.
; Otherwise ask the agent to register. Agent could have registered,
; switch fails, heartbeats missed, conductor marks it as dead, remvoes
; from tracker, switch fixed, agent responds to heartbeat.
;-----------------------------------------------------------------------
(defmethod dispatch :heartbeat-ack [{:keys[agent-id]}]
  (if (-> @app-state state/agent-tracker (track/agent-exists? agent-id))
    (swap! app-state #(state/heartbeat-rcvd %1 agent-id (util/now-ms)))
    (publish agent-id {:msg :agents-register})))

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
(defmethod dispatch :job-finished [data]
  (when-job-finished data))

; TODO: send an ack
(defmethod dispatch :node-save [{:keys[node-id]}]
  (log/debug "Node save for node-id: " node-id)
  (let [n (db/get-node-by-id node-id)]
    (swap! app-state #(state/save-node %1 n))))

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

    (doseq [{:keys[node-schedule-id cron-expression]} (cache/schedule-assocs-with-cron-expr-for-node node-id)]
      (quartz/schedule-cron-job! node-schedule-id node-id cron-expression))))



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
              (cache/put-jobs (db/ls-jobs))
              (cache/put-workflows (db/ls-workflows))
              (cache/put-schedules (db/ls-schedules))
              (cache/put-assocs (db/ls-node-schedules)))]
    (swap! app-state #(state/set-node-schedule-cache %1 c))))

(defn- populate-quartz-triggers
  "Populates quartz triggers by reading from the cache."
  []
  (let [nsc (-> @app-state state/node-schedule-cache)]
    (doseq [{:keys[node-schedule-id node-id cron-expression]} (cache/schedule-assocs-with-cron-expr nsc)]
      (quartz/schedule-cron-job! node-schedule-id node-id cron-expression))))


(defn init [pub-port sub-port]

  (reset! app-state (state/new-state))
  
  (log/info "Connecting to database.")
  (conf/init-db)


  (let [host "*" bind? true]
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
