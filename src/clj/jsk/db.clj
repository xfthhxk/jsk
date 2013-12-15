(ns jsk.db
  "Database access"
  (:require [clojure.string :as string]
            [taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal spy with-log-level)])
  (:use [korma core db]))


(def synthetic-workflow-id 1)


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
  (entity-fields :node-id :node-name :node-type-id :node-desc :is-system :is-enabled :create-ts :creator-id :update-ts :updater-id))

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
              :node.create-ts
              :node.creator-id
              :node.update-ts
              :node.updater-id
              [:node.node-name :job-name] [:node.node-desc :job-desc])
      (join :inner :node (= :job-id :node.node-id))))

(def base-workflow-query
  (-> (select* workflow)
      (fields :workflow-id
              :node.is-enabled
              :node.is-system
              :node.create-ts
              :node.creator-id
              :node.update-ts
              :node.updater-id
              [:node.node-name :workflow-name] [:node.node-desc :workflow-desc])
      (join :inner :node (= :workflow-id :node.node-id))))

;-----------------------------------------------------------------------
; Node lookups
;-----------------------------------------------------------------------
(defn ls-nodes
  "Gets node id, nm, and type for all non-system workflows."
  []
  (select node
          (where {:is-system false})))

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
  (select base-workflow-query
          (where {:node.is-system false})))

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
              :creator-id user-id
              :updater-id user-id}]
    (-> (insert node (values data))
        extract-identity)))

(defn- update-node! [node-id node-nm node-desc enabled? user-id]
  (let [data {:node-name node-nm
              :node-desc node-desc
              :is-enabled enabled?
              :updater-id user-id
              :update-ts (now)}]
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
  (exec-raw ["select   js.job_schedule_id
                     , s.*
                from   job_schedule js
                join   schedule s
                  on   js.schedule_id = s.schedule_id
               where   job_id = ?" [job-id]] :results))



;-----------------------------------------------------------------------
; Associates a job to a set of schedule-ids.
; schedule-ids is a set of integer ids
;-----------------------------------------------------------------------
(defn assoc-schedules!
  ([{:keys [job-id schedule-ids]} user-id]
    (assoc-schedules! job-id schedule-ids user-id))

  ([job-id schedule-ids user-id]
    (let [schedule-id-set (set schedule-ids)
          data {:job-id job-id :creator-id user-id}
          insert-maps (map #(assoc %1 :schedule-id %2) (repeat data) schedule-id-set)]
      (if (not (empty? insert-maps))
        (insert job-schedule (values insert-maps))))))

;-----------------------------------------------------------------------
; Gets all enabled jobs with schedules.
;-----------------------------------------------------------------------
(defn enabled-jobs-schedule-info []
  (exec-raw ["select
                     js.job_schedule_id
                   , js.job_id
                   , s.cron_expression
                from
                     job_schedule js
                join schedule     s
                  on js.schedule_id = s.schedule_id
                join job          j
                  on js.job_id = j.job_id
                join node         n
                  on j.job_id = n.node_id
               where n.is_enabled = 1"]
            :results))



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
  (entity-fields :status-id :start-ts :finish-ts))

(defentity execution-workflow
  (pk :execution-workflow-id)
  (entity-fields :execution-id :workflow-id :root :status-id :start-ts :finish-ts))

(defentity execution-vertex
  (pk :execution-vertex-id)
  (entity-fields :execution-id :execution-workflow-id :node-id :runs-execution-workflow-id :status-id :start-ts :finish-ts))

; some strange issue with substituting params in this query
(def ^:private child-workflow-sql
   " with wf(workflow_id) as
     (
       select
              w.workflow_id
         from workflow_vertex wv
         join workflow w
           on wv.node_id = w.workflow_id
        where wv.workflow_id = <wf-id>

      union all

      select
             w.workflow_id
        from wf
        join workflow_vertex wv
          on wf.workflow_id = wv.workflow_id
        join workflow w
          on wv.node_id = w.workflow_id
     )
     select wf.workflow_id
       from wf ")

(defn- children-workflows
  "For the wf-id specified recursively get all workflows it uses.
   Answers with a seq of ints."
  [wf-id]
  (let [q (string/replace child-workflow-sql #"<wf-id>" (str wf-id))]
    (->> (exec-raw [q []] :results)
         (map :workflow-id)
         doall)))

(defn- insert-execution-workflows
  "Creates rows in execution-workflows table and returns a map of
   wf-id to execution-workflow-id."
  [exec-id wf child-wfs]
  (debug "insert execution-workflows: exec-id: " exec-id
         ", wf: " wf ", child-wfs: " child-wfs)

  (reduce (fn [ans id]
            (->> (insert execution-workflow
                   (values {:execution-id exec-id
                            :workflow-id id
                            :root (= id wf)
                            :status-id unexecuted-status}))
                extract-identity (assoc ans id)))
          {}
          (conj child-wfs wf)))

(defn- snapshot-execution
  "Creates a snapshot of the workflow vertices and edges to allow tracking
   for a particular execution."
  [exec-id wf-id]

  ; determine all children workflows recursively and
  ; create records in the execution_workflow table for all child workflows and the parent
  (let [child-wfs (children-workflows wf-id)]
    (insert-execution-workflows exec-id wf-id child-wfs))

  (exec-raw
   ["insert into execution_vertex (execution_id, execution_workflow_id, node_id, status_id, layout)
       select
              ew.execution_id
            , ew.execution_workflow_id
            , wv.node_id
            , ?
            , wv.layout
         from execution_workflow ew
         join workflow_vertex    wv
           on ew.workflow_id  = wv.workflow_id
        where ew.execution_id = ? " [unexecuted-status exec-id]])

  (exec-raw
   ["insert into execution_edge (execution_id, vertex_id, next_vertex_id, success)
            select
                   ew.execution_id
                 , ef.execution_vertex_id
                 , et.execution_vertex_id
                 , e.success
              from workflow_edge e
              join workflow_vertex f
                on e.vertex_id = f.workflow_vertex_id
              join workflow_vertex t
                on e.next_vertex_id = t.workflow_vertex_id
              join execution_workflow ew
                on ew.workflow_id = f.workflow_id
               and ew.workflow_id = t.workflow_id
              join execution_vertex ef
                on ew.execution_workflow_id = ef.execution_workflow_id
               and ef.node_id = f.node_id
              join execution_vertex et
                on ew.execution_workflow_id = et.execution_workflow_id
               and et.node_id = t.node_id
             where ew.execution_id = ?  " [exec-id]]))

(defn- new-execution*
  "Sets up a new execution returning the newly created execution-id."
  []
  (-> (insert execution
        (values {:status-id started-status
                 :start-ts  (now)}))
      extract-identity))


(defn new-execution!
  "Sets up a new execution returning the newly created execution-id."
  [wf-id]
  (let [exec-id (new-execution*)]
    (snapshot-execution exec-id wf-id)
    exec-id))


(defn- snapshot-synthetic-workflow
  "Creates a snapshot for the synthetic workflow which consists of 1 job.
   Answers with the execution-vertex-id."
  [exec-id exec-wf-id job-id status-id]

  (-> (insert execution-vertex
        (values {:execution-id exec-id
                 :execution-workflow-id exec-wf-id :node-id job-id
                 :status-id    status-id    :layout ""}))
      extract-identity))


(defn synthetic-workflow-started
  "Sets up an synthetic workflow for the job specified."
  [job-id]
  (let [exec-id (new-execution*)
        id-map  (insert-execution-workflows exec-id synthetic-workflow-id [])
        exec-wf-id (id-map synthetic-workflow-id)
        exec-vertex-id (snapshot-synthetic-workflow exec-id exec-wf-id job-id unexecuted-status)
        job-name (get-job-name job-id)]

    {:execution-id exec-id
     :wf-id synthetic-workflow-id
     :exec-wf-id exec-wf-id
     :exec-vertex-id exec-vertex-id
     :status unexecuted-status
     :job-nm job-name
     :job-id job-id
     :node-type job-type-id}))


(defn execution-vertices
  "Answers with all rows which represent vertices for this execution graph."
  [exec-id]
  (select execution-vertices (where {:execution-id exec-id})))

(defn get-execution-graph
  "Recursively gets the edges for the workflow execution specified.
   Gets all data for all nested workflows."
  [id]
  (exec-raw
    ["select
             ew.execution_workflow_id
           , ew.workflow_id
           , ew.root                      as is_root_wf
           , f.node_id                    as src_id
           , f.status_id                  as src_status_id
           , f.start_ts                   as src_start_ts
           , f.finish_ts                  as src_finish_ts
           , f.execution_vertex_id        as src_exec_vertex_id
           , f.runs_execution_workflow_id as src_runs_execution_workflow_id
           , t.node_id                    as dest_id
           , t.status_id                  as dest_status_id
           , t.start_ts                   as dest_start_ts
           , t.finish_ts                  as dest_finish_ts
           , t.execution_vertex_id        as dest_exec_vertex_id
           , t.runs_execution_workflow_id as dest_runs_execution_workflow_id
           , e.success
           , fn.node_name                 as src_name
           , fn.node_type_id              as src_type
           , tn.node_name                 as dest_name
           , tn.node_type_id              as dest_type
           , f.layout                     as src_layout
           , t.layout                     as dest_layout
        from execution_workflow ew
        join execution_vertex   f
          on ew.execution_workflow_id = f.execution_workflow_id
        join execution_edge     e
          on f.execution_vertex_id = e.vertex_id
        join execution_vertex t
          on e.next_vertex_id = t.execution_vertex_id
        join node fn
          on f.node_id = fn.node_id
        join node tn
          on t.node_id = tn.node_id
       where e.execution_id = ? " [id]] :results))


(defn execution-aborted [exec-id ts]
  ; all vertices which are not finished and status id is not 1
  ; all execution workflows which are not finished and status id is not 1
  ; execution tbl
  (transaction
   (update execution-vertex
           (set-fields {:status-id aborted-status :finish-ts ts})
           (where (and (= :execution-id exec-id)
                       (not= :start-ts nil)
                       (= :finish-ts nil))))

   (update execution-workflow
           (set-fields {:status-id aborted-status :finish-ts ts})
           (where (and (= :execution-id exec-id)
                       (not= :start-ts nil)
                       (= :finish-ts nil))))

   (update execution
           (set-fields {:status-id aborted-status :finish-ts ts})
           (where {:execution-id exec-id}))))


(defn update-execution-status
  "Updates the execution status to the status specified."
  [execution-id status ts]
  (update execution
    (set-fields {:status-id status :finish-ts ts})
    (where {:execution-id execution-id})))

(defn execution-finished
  "Marks the execution as finished and sets the status."
  [execution-id success? finish-ts]
  (update-execution-status execution-id
                           (if success? finished-success finished-error)
                           finish-ts))


(defn execution-vertex-started [exec-vertex-id ts]
  (update execution-vertex
    (set-fields {:status-id started-status :start-ts ts})
    (where {:execution-vertex-id exec-vertex-id})))

(defn execution-vertex-finished [exec-vertex-id status-id ts]
  (update execution-vertex
    (set-fields {:status-id status-id :finish-ts ts})
    (where {:execution-vertex-id exec-vertex-id})))


(defn workflow-finished
  "Marks the workflow as finished and sets the status in both the execution-workflow
   table and in execution-vertex."
  [exec-wf-id success? finish-ts]
  (let [status (if success? finished-success finished-error)]
    (update execution-workflow
      (set-fields {:status-id status :finish-ts finish-ts})
      (where {:execution-workflow-id exec-wf-id}))))

(defn workflow-started
  "Marks the workflow as finished and sets the status in both the
   execution-workflow and execution-vertex tables."
  [exec-wf-id start-ts]
  (update execution-workflow
    (set-fields {:status-id started-status :start-ts start-ts})
    (where {:execution-workflow-id exec-wf-id})))


(defn workflows-and-vertices-finished
  "Marks the workflow and the execution vertex which represents the
   workflow as finished with the supplied status."
  [exec-vertex-ids exec-wf-ids success? ts]
  (let [status-id (if success? finished-success finished-error)]
    (transaction
      (update execution-workflow
        (set-fields {:status-id status-id :finish-ts ts})
        (where (in :execution-workflow-id exec-wf-ids)))
      (update execution-vertex
        (set-fields {:status-id status-id :finish-ts ts})
        (where (in :execution-vertex-id exec-vertex-ids))))))


(defn set-vertex-runs-execution-workflow-mapping
  "m is a map of execution-vertex-ids to executon-workflow-ids.
   The mapping represents the vertices which are workflow nodes
   and the execution workflow each is responsible for running."
  [m]
  (transaction
   (doseq [[vertex-id wf-id] (vec m)]
     (update execution-vertex
             (set-fields {:runs-execution-workflow-id wf-id})
             (where {:execution-vertex-id vertex-id})))))


;-----------------------------------------------------------------------
; Execution visualization queries
;-----------------------------------------------------------------------
(def ^:private root-exec-sql
  "select
         e.execution_id
       , e.status_id
       , e.start_ts
       , e.finish_ts
       , ew.execution_workflow_id as root_execution_workflow_id
       , ew.workflow_id
       , n.node_name as workflow_name
    from execution e
    join execution_workflow ew
      on e.execution_id = ew.execution_id
     and ew.root = true
    join node n
      on ew.workflow_id = n.node_id
   where e.execution_id = ? ")

(defn get-execution-details
  "Lookup the execution data for visualization purposes."
  [execution-id]
  (first (exec-raw [root-exec-sql [execution-id]] :results)))

(def ^:private exec-wf-sql
 "select
       ew.execution_workflow_id
     , ew.workflow_id
     , ew.status_id
     , ew.start_ts
     , ew.finish_ts
     , n.node_name as workflow_name
  from execution_workflow ew
  join node n
    on ew.workflow_id = n.node_id
 where ew.execution_workflow_id = ? ")

(def ^:private exec-wf-nodes-sql
  "  select
           sv.execution_vertex_id as src_vertex_id
         , sv.runs_execution_workflow_id as src_runs_execution_workflow_id
         , sn.node_id             as src_node_id
         , sn.node_name           as src_node_name
         , sn.node_type_id        as src_node_type
         , sv.status_id           as src_status_id
         , sv.start_ts            as src_start_ts
         , sv.finish_ts           as src_finish_ts
         , sv.layout              as src_layout
         , dv.execution_vertex_id as dest_vertex_id
         , dv.runs_execution_workflow_id as dest_runs_execution_workflow_id
         , dn.node_id             as dest_node_id
         , dn.node_name           as dest_node_name
         , dn.node_type_id        as dest_node_type
         , dv.status_id           as dest_status_id
         , dv.start_ts            as dest_start_ts
         , dv.finish_ts           as dest_finish_ts
         , dv.layout              as dest_layout
         , e.success
      from execution_vertex sv
      join node sn
        on sv.node_id = sn.node_id
 left join execution_edge e
        on sv.execution_vertex_id = e.vertex_id
 left join execution_vertex dv
        on sv.execution_workflow_id = dv.execution_workflow_id
       and e.next_vertex_id = dv.execution_vertex_id
 left join node dn
        on dv.node_id = dn.node_id
     where sv.execution_workflow_id = ? ")

(defn get-execution-workflow-details
  "Lookup the execution workflow data for visualization purposes."
  [exec-wf-id]
  {:wf-info (first (exec-raw [exec-wf-sql [exec-wf-id]] :results))
   :nodes (exec-raw [exec-wf-nodes-sql [exec-wf-id]] :results)})

; Top part of union selects workflows
; bottom part selects synthetic workflows
(def ^:private execution-search-sql
  "select
       e.execution_id
     , wfn.node_name as node_name
     , s.execution_status_id
     , s.status_code
     , e.start_ts
     , e.finish_ts
  from execution e
  join execution_workflow ew
    on e.execution_id = ew.execution_id
  join node wfn
    on ew.workflow_id = wfn.node_id
  join execution_status s
    on e.status_id = s.execution_status_id
 where ew.root = true
   and wfn.is_system = false
   and e.start_ts between ? and ?
union all
select
       e.execution_id
     , jn.node_name as job_name
     , s.execution_status_id
     , s.status_code
     , e.start_ts
     , e.finish_ts
  from execution e
  join execution_workflow ew
    on e.execution_id = ew.execution_id
  join node wfn
    on ew.workflow_id = wfn.node_id
  join execution_status s
    on e.status_id = s.execution_status_id
  join execution_vertex v
    on ew.execution_workflow_id = v.execution_workflow_id
  join node jn
    on v.node_id = jn.node_id
 where ew.root = true
   and wfn.is_system = true
   and e.start_ts between ? and ?")

(defn execution-search
  "Does a basic search for executions between the timestamps specified."
  [start-ts finish-ts]
  (exec-raw [execution-search-sql [start-ts finish-ts start-ts finish-ts]] :results))

(def ^:private execution-name-sql
  "  select
         wf.node_name wf_name
    from execution e
    join execution_workflow ew
      on e.execution_id = ew.execution_id
     and ew.root = true
    join node wf
      on ew.workflow_id = wf.node_id
     and wf.is_system = false
   where e.execution_id = ?
union all
  select
         jn.node_name
    from execution e
    join execution_workflow ew
      on e.execution_id = ew.execution_id
     and ew.root = true
    join node wf
      on ew.workflow_id = wf.node_id
     and wf.is_system = true
    join execution_vertex v
      on ew.execution_workflow_id = v.execution_workflow_id
    join node jn
      on v.node_id = jn.node_id
   where e.execution_id = ? ")

(defn get-execution-name [exec-id]
  (->> (exec-raw [execution-name-sql [exec-id exec-id]] :results)
       (map :wf-name)
       first))



(def ^:private is-synthetic-wf-sql
  "select 1
     from execution e
     join execution_workflow ew
       on e.execution_id = ew.execution_id
    where ew.root = true
      and e.execution_id = ?
      and ew.workflow_id = ? ")

(defn synthetic-workflow-execution? [exec-id]
  (-> (exec-raw [is-synthetic-wf-sql [exec-id synthetic-workflow-id]] :results)
      count
      (= 1)))

(def ^:private synthetic-wf-resume-sql
 "select
       e.execution_id
     , ew.execution_workflow_id as exec_wf_id
     , ew.workflow_id           as wf_id
     , v.execution_vertex_id    as exec_vertex_id
     , j.node_id                as job_id
     , j.node_name              as job_nm
     , j.node_type_id           as node_type
  from execution e
  join execution_workflow ew
    on e.execution_id = ew.execution_id
  join execution_vertex v
    on ew.execution_workflow_id = v.execution_workflow_id
  join node j
    on v.node_id = j.node_id
 where e.execution_id = ? ")

(defn synthetic-workflow-resumption [exec-id]
  (first (exec-raw [synthetic-wf-resume-sql [exec-id]] :results)))










