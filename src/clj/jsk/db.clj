(ns jsk.db
  "Database access"
  (:require [clojure.string :as str]
            [taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal spy with-log-level)])
  (:use [korma core db]))

; current date time
(defn now [] (java.util.Date.))


;-----------------------------------------------------------------------
; Extracts the id for the last inserted row
;-----------------------------------------------------------------------
(defn extract-identity [m]
  (first (vals m))) ; doing in a db agnostic way

(defn id? [id]
  (and id (> id 0)))


(defentity app-user
  (pk :app-user-id)
  (entity-fields :app-user-id :first-name :last-name :email))

(defn user-for-email [email]
  (first (select app-user (where {:email email}))))

(def job-type-id 1)
(def workflow-type-id 2)

(defentity node
  (pk :node-id)
  (entity-fields :node-id :node-name :node-type-id :node-desc :is-enabled :created-at :create-user-id :updated-at :update-user-id))

(defentity job
  (pk :job-id)
  (entity-fields :job-id :execution-directory :command-line :agent-id :max-concurrent :max-retries))

(defentity workflow
  (pk :workflow-id)
  (entity-fields :workflow-id))

(defentity workflow-vertex
  (pk :workflow-vertex-id)
  (entity-fields :workflow-vertex-id :workflow-id :node-id :layout))

(defentity workflow-edge
  (pk :workflow-edge-id)
  (entity-fields :workflow-edge-id :vertex-id :next-vertex-id :success))

(defentity job-schedule
  (pk :job-schedule-id)
  (entity-fields :job-schedule-id :job-id :schedule-id))

(def base-job-query
  (-> (select* job)
      (fields :job-id
              :execution-directory
              :command-line
              :agent-id
              :max-concurrent
              :max-retries
              :node.is-enabled
              :node.created_at
              :node.create-user-id
              :node.updated-at
              :node.update-user-id
              [:node.node-name :job-name] [:node.node-desc :job-desc])
      (join :inner :node (= :job-id :node.node-id))))

(def base-workflow-query
  (-> (select* workflow)
      (fields :workflow-id
              :node.is-enabled
              :node.is-system
              :node.created_at
              :node.create-user-id
              :node.updated_at
              :node.update-user-id
              [:node.node-name :workflow-name] [:node.node-desc :workflow-desc])
      (join :inner :node (= :workflow-id :node.node-id))))

;-----------------------------------------------------------------------
; Job lookups
;-----------------------------------------------------------------------
(defn ls-jobs
  "Lists all jobs"
  []
  (select base-job-query))

(defn enabled-jobs
  "Gets all active jobs."
  []
  (select base-job-query
    (where {:node.is-enabled true})))

(defn get-job
  "Gets a job for the id specified"
  [id]
  (first (select base-job-query (where {:job-id id}))))

(defn get-job-name
  "Answers with the job name for the job id, otherwise nil if no such job."
  [id]
  (if-let [j (get-job id)]
    (:job-name j)))


(defn get-node-by-name
  "Gets a job by name if one exists otherwise returns nil"
  [nm]
  (first (select node (where {:node-name nm}))))

(defn get-job-by-name
  "Gets a job by name if one exists otherwise returns nil"
  [nm]
  (first (select base-job-query (where {:node.node-name nm}))))

(defn job-name-exists?
  "Answers true if job name exists"
  [nm]
  (-> nm get-job-by-name nil? not))


;-----------------------------------------------------------------------
; Workflow lookups
;-----------------------------------------------------------------------
(defn ls-workflows
  "Lists all workflows"
  []
  (select base-workflow-query))

(defn enabled-workflows
  "Gets all active workflows."
  []
  (select base-workflow-query
    (where {:node.is-enabled true})))

(defn get-workflow
  "Gets a workflow for the id specified"
  [id]
  (first (select base-workflow-query (where {:workflow-id id}))))

(defn get-workflow-name
  "Answers with the workflow name for the job id, otherwise nil if no such job."
  [id]
  (if-let [j (get-workflow id)]
    (:workflow-name j)))

(defn get-workflow-by-name
  "Gets a job by name if one exists otherwise returns nil"
  [nm]
  (first (select base-workflow-query (where {:node.node-name nm}))))

(defn workflow-name-exists?
  "Answers true if workflow name exists"
  [nm]
  (-> nm get-workflow-by-name nil? not))



;-----------------------------------------------------------------------
; Insert a node. Answer with the inserted node's row id
;-----------------------------------------------------------------------
(defn- insert-node! [type-id node-nm node-desc enabled? user-id]
  (let [data {:node-name node-nm
              :node-desc node-desc
              :node-type-id type-id
              :is-enabled   enabled?
              :is-system false           ; system nodes should just be added via sql
              :create-user-id user-id
              :update-user-id user-id}]
    (-> (insert node (values data))
        extract-identity)))

(defn- update-node! [node-id node-nm node-desc enabled? user-id]
  (let [data {:node-name node-nm
              :node-desc node-desc
              :is-enabled enabled?
              :update-user-id user-id
              :updated-at (now)}]
    (update node (set-fields data)
      (where {:node-id node-id}))
    node-id))


(def insert-job-node! (partial insert-node! job-type-id))
(def insert-workflow-node! (partial insert-node! workflow-type-id))


;-----------------------------------------------------------------------
; Insert a job. Answers with the inserted job's row id.
;-----------------------------------------------------------------------
(defn- insert-job! [m user-id]
  (transaction
    (let [{:keys [job-name job-desc is-enabled execution-directory command-line max-concurrent max-retries agent-id]} m
          node-id (insert-job-node! job-name job-desc is-enabled user-id)
          data {:execution-directory execution-directory
                :command-line command-line
                :job-id node-id
                :agent-id agent-id
                :max-concurrent max-concurrent
                :max-retries max-retries}]
      (insert job (values data))
      node-id)))


;-----------------------------------------------------------------------
; Update an existing job.
; Answers with the job-id if update is successful.
;-----------------------------------------------------------------------
(defn- update-job! [m user-id]
  (let [{:keys [job-id job-name job-desc is-enabled execution-directory command-line max-concurrent max-retries]} m
        data {:execution-directory execution-directory
              :command-line command-line
              :max-concurrent max-concurrent
              :max-retries max-retries}]
    (transaction
      (update-node! job-id job-name job-desc is-enabled user-id)
      (update job (set-fields data)
        (where {:job-id job-id})))
    job-id))

(defn save-job [{:keys [job-id] :as j} user-id]
  (if (id? job-id)
      (update-job! j user-id)
      (insert-job! j user-id)))

;-----------------------------------------------------------------------
; Workflow save
;-----------------------------------------------------------------------
(defn- insert-workflow! [m user-id]
  (let [{:keys [workflow-name workflow-desc is-enabled]} m
        node-id (insert-workflow-node! workflow-name workflow-desc is-enabled user-id)]
    (insert workflow (values {:workflow-id node-id}))
    node-id))

(defn- update-workflow! [{:keys [workflow-id] :as m} user-id]
  workflow-id)

(defn save-workflow [{:keys [workflow-id] :as w} user-id]
  (if (id? workflow-id)
    (update-workflow! w user-id)
    (insert-workflow! w user-id)))


;-----------------------------------------------------------------------
; Schedule ids associated with the specified job id.
;-----------------------------------------------------------------------
(defn schedules-for-job [job-id]
  (->> (select job-schedule (where {:job-id job-id}))
       (map :schedule-id)
       set))

;-----------------------------------------------------------------------
; Job schedule ids associated with the specified job id.
;-----------------------------------------------------------------------
(defn job-schedules-for-job [job-id]
  (->> (select job-schedule (where {:job-id job-id}))
       (map :job-schedule-id)
       set))


;-----------------------------------------------------------------------
; Deletes from job-schedule all rows matching job-schedule-ids
; Also removes from quartz all jobs matching the job-schedule-id
; ie the triggers IDd by job-scheduler-id
;-----------------------------------------------------------------------
(defn rm-job-schedules! [job-schedule-ids]
  (delete job-schedule (where {:job-schedule-id [in job-schedule-ids]})))

;-----------------------------------------------------------------------
; Deletes from job-schedule all rows matching job-id
;-----------------------------------------------------------------------
(defn rm-schedules-for-job! [job-id]
  (-> job-id job-schedules-for-job rm-job-schedules!))

(defn get-job-schedule-info [job-id]
  (exec-raw ["select js.job_schedule_id, s.* from job_schedule js join schedule s on js.schedule_id = s.schedule_id where job_id = ?" [job-id]] :results))



;-----------------------------------------------------------------------
; Associates a job to a set of schedule-ids.
; schedule-ids is a set of integer ids
;-----------------------------------------------------------------------
(defn assoc-schedules!
  ([{:keys [job-id schedule-ids]} user-id]
    (assoc-schedules! job-id schedule-ids user-id))

  ([job-id schedule-ids user-id]
    (let [schedule-id-set (set schedule-ids)
          data {:job-id job-id :create-user-id user-id}
          insert-maps (map #(assoc %1 :schedule-id %2) (repeat data) schedule-id-set)]
      (if (not (empty? insert-maps))
        (insert job-schedule (values insert-maps))))))


;-----------------------------------------------------------------------
; Workflow vertices and edges
;-----------------------------------------------------------------------
(defn- rm-workflow-edge [workflow-id]
  "Deletes workflow edges for the workflow id"
  (exec-raw ["delete
                from workflow_edge e
               where exists (select 1
                               from workflow_vertex v
                              where v.workflow_id = ?
                                and e.vertex_id = v.workflow_vertex_id)"
               [workflow-id]]))

(defn- rm-workflow-vertex
  "Deletes workflow vertices for the workflow id"
  [workflow-id]
  (delete workflow-vertex (where {:workflow-id workflow-id})))

(defn rm-workflow-graph
  "Deletes from the database the edges and vertices
   associated with the specified workflow-id."
  [workflow-id]
  (rm-workflow-edge workflow-id)
  (rm-workflow-vertex workflow-id))

(defn save-workflow-edge
  "Creates a row in the workflow_edge table.
   vertex-id and next-vertex-id are ids from the workflow_vertex
   table. success? is a boolean."
  [vertex-id next-vertex-id success?]
  (let [m {:vertex-id vertex-id
           :next-vertex-id next-vertex-id
           :success success?}]
    (-> (insert workflow-edge (values m))
         extract-identity)))

(defn save-workflow-vertex [workflow-id node-id layout]
  "Creates a row in the workflow_vertex table.
   workflow-id is an int. node-id is a key from the node
   table. layout is a string describing the layout of the
   vertex on the front end (css positions perhaps)."
  (let [m {:workflow-id workflow-id
           :node-id node-id
           :layout layout}]
    (-> (insert workflow-vertex (values m))
         extract-identity)))


(defn get-workflow-graph
  "Gets the edges for the workflow specified."
  [id]
  (exec-raw
   ["select
            fv.node_id      as src_id
          , tv.node_id      as dest_id
          , e.success
          , fn.node_name    as src_name
          , fn.node_type_id as src_type
          , tn.node_name    as dest_name
          , tn.node_type_id as dest_type
          , fv.layout       as src_layout
          , tv.layout       as dest_layout
       from workflow_vertex fv
       join workflow_edge  e
         on fv.workflow_vertex_id = e.vertex_id
       join workflow_vertex tv
         on e.next_vertex_id = tv.workflow_vertex_id
       join node fn
         on fv.node_id = fn.node_id
       join node tn
         on tv.node_id = tn.node_id
      where fv.workflow_id = tv.workflow_id
        and fv.workflow_id = ?" [id]] :results))


;-----------------------------------------------------------------------
; Job execution stuff should be in another place likely
; schedule-ids is a set of integer ids
;-----------------------------------------------------------------------
; These are tied to what is in the job_execution_status table
(def unexecuted-status 1)
(def started-status 2)
(def finished-success 3)
(def finished-error 4)
(def aborted-status 5)

(defentity execution
  (pk :execution-id)
  (entity-fields :workflow-id :status-id :started-at :finished-at))

(defentity execution-vertex
  (pk :execution-vertex-id)
  (entity-fields :execution-id :node-id :status-id :started-at :finished-at))

(defn- snapshot-workflow
  "Creates a snapshot of the workflow vertices and edges to allow tracking
   for a particular execution."
  [execution-id wf-id]

  (exec-raw
   ["insert into execution_vertex (execution_id, node_id, status_id, layout)
       select ?, wv.node_id, ?, wv.layout
         from workflow_vertex wv
        where wv.workflow_id = ?" [execution-id unexecuted-status wf-id]])

  (exec-raw
   ["insert into execution_edge (execution_id, vertex_id, next_vertex_id, success)
       select
              ef.execution_id
            , ef.execution_vertex_id
            , et.execution_vertex_id
            , e.success
         from workflow_vertex f
         join workflow_edge   e
           on f.workflow_vertex_id = e.vertex_id
         join workflow_vertex t
           on e.next_vertex_id = t.workflow_vertex_id
         join execution_vertex ef
           on f.node_id = ef.node_id
         join execution_vertex et
           on t.node_id = et.node_id
       where f.workflow_id = t.workflow_id
         and f.workflow_id = ?
         and ef.execution_id = et.execution_id
         and ef.execution_id = ? " [wf-id execution-id]]))

(defn- workflow-started*
  "Creates a row in the execution table and returns the id
   of the newly created row."
  [wf-id started-at]
  (-> (insert execution
        (values {:workflow-id wf-id
                 :status-id started-status
                 :started-at started-at}))
      extract-identity))

(defn workflow-started
  "Returns a map with keys: :event, :execution-id and :ts"
  [wf-id]
  (let [ts (now)
        execution-id (workflow-started* wf-id ts)
        wf-name (get-workflow-name wf-id)]

    (snapshot-workflow execution-id wf-id)

    {:event :start
     :execution-id execution-id
     :start-ts ts
     :workflow-id wf-id
     :workflow-name wf-name}))

(defn execution-vertices
  "Answers with all rows which represent vertices for this execution graph."
  [exec-id]
  (select execution-vertices (where {:execution-id exec-id})))

(defn get-execution-graph
  "Gets the edges for the workflow execution specified."
  [id]
  (exec-raw
   ["select
            fv.node_id             as src_id
          , fv.status_id           as src_status_id
          , fv.started_at          as src_started_at
          , fv.finished_at         as src_finished_at
          , fv.execution_vertex_id as src_exec_vertex_id
          , tv.node_id             as dest_id
          , tv.status_id           as dest_status_id
          , tv.started_at          as dest_started_at
          , tv.finished_at         as dest_finished_at
          , tv.execution_vertex_id as dest_exec_vertex_id
          , e.success
          , fn.node_name           as src_name
          , fn.node_type_id        as src_type
          , tn.node_name           as dest_name
          , tn.node_type_id        as dest_type
          , fv.layout              as src_layout
          , tv.layout              as dest_layout
       from execution ex
       join execution_vertex fv
         on ex.execution_id = fv.execution_id
       join execution_edge  e
         on fv.execution_vertex_id = e.vertex_id
       join execution_vertex tv
         on e.next_vertex_id = tv.execution_vertex_id
       join node fn
         on fv.node_id = fn.node_id
       join node tn
         on tv.node_id = tn.node_id
      where
            ex.execution_id = ?" [id]] :results))


(defn workflow-finished [execution-id success? finished-ts]
  "Marks the workflow as finished and sets the status."

  (let [status (if success? finished-success finished-error)]
    (update execution
      (set-fields {:status-id status :finished-at finished-ts})
      (where {:execution-id execution-id}))))


(defn execution-vertex-started [exec-vertex-id]
  (update execution-vertex
    (set-fields {:status-id started-status :started-at (now)})
    (where {:execution-vertex-id exec-vertex-id})))

(defn execution-vertex-finished [exec-vertex-id status-id]
  (update execution-vertex
    (set-fields {:status-id status-id :finished-at (now)})
    (where {:execution-vertex-id exec-vertex-id})))




(def counter (atom 1))

(defn job-started [job-id]
  (info "job-started with id: " job-id)
  {:execution-id (swap! counter inc)})

(defn job-finished [exec-id success? finish-ts]
  (info "job finished. exec-id: " exec-id))











