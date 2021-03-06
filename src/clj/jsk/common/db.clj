(ns jsk.common.db
  "Database access"
  (:refer-clojure :exclude [agent])
  (:require
            [jsk.common.data :as data]
            [jsk.common.conf :as conf]
            [jsk.common.util :as util]
            [clojure.string :as string]
            [clj-time.core :as ctime]
            [taoensso.timbre :as log])
  (:use [korma core db]))

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


(defentity agent
  (pk :agent-id)
  (entity-fields :agent-id :agent-name :creator-id :create-ts :updater-id :update-ts))


(defentity node-directory
  (pk :node-directory-id)
  (entity-fields :node-directory-id :node-directory-name :parent-directory-id))

(defentity node
  (pk :node-id)
  (entity-fields :node-id :node-name :node-type-id :node-desc :is-system :is-enabled :node-directory-id :create-ts :creator-id :update-ts :updater-id))

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

(defentity schedule
  (pk :schedule-id)
  (entity-fields :schedule-id :schedule-name :schedule-desc :cron-expression))

(defentity node-schedule
  (pk :node-schedule-id)
  (entity-fields :node-schedule-id :node-id :schedule-id))

(defentity alert
  (pk :alert-id)
  (entity-fields :alert-id :alert-name :alert-desc :recipients :subject :body :is-for-error))

(defentity node-alert
  (pk :node-alert-id)
  (entity-fields :node-alert-id :node-id :alert-id))

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
              :node.node-type-id
              :node.node-directory-id
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
              :node.node-type-id
              :node.node-directory-id
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
  "Gets a node by name if one exists otherwise returns nil"
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

(defn get-node-by-id
  "Gets a node by name if one exists otherwise returns nil.
   If node-type-id is specified retrieves the job or workflow rather than
   just the shared node data."
  ([id]
    (first (select node (where {:node-id id}))))
  ([node-id node-type-id]
     (cond
      (util/workflow-type? node-type-id) (get-workflow node-id)
      (util/job-type? node-type-id) (get-job node-id)
      :else (throw (AssertionError. (str "Unknown node-type-id: " node-type-id))))))

;-----------------------------------------------------------------------
; Insert a schedule
;-----------------------------------------------------------------------
(defn insert-schedule! [m user-id]
  (let [ts (util/now)
        merged-map (merge (dissoc m :schedule-id) {:creator-id user-id :updater-id user-id
                                                   :create-ts ts :update-ts ts})]
    (log/info "Creating new schedule: " merged-map)
    (-> (insert schedule (values merged-map))
         extract-identity)))


;-----------------------------------------------------------------------
; Update an existing schedule
; Answers with the schedule-id if update is successful.
;-----------------------------------------------------------------------
(defn update-schedule! [{:keys [schedule-id] :as m} user-id]
  (let [merged-map (merge m {:updater-id user-id :update-ts (util/now)})]
    (log/info "Updating schedule: " m)
    (update schedule (set-fields (dissoc m :schedule-id))
      (where {:schedule-id schedule-id})))
  schedule-id)

(defn rm-schedule! [schedule-id]
  (delete schedule (where {:schedule-id schedule-id})))

;-----------------------------------------------------------------------
; Schedule lookups
;-----------------------------------------------------------------------
(defn ls-schedules
  []
  "Lists all schedules"
  (select schedule))

(defn get-schedule
  "Gets a schedule for the id specified"
  [id]
  (first (select schedule
          (where {:schedule-id id}))))

(defn get-schedules [ids]
  (select schedule
    (where {:schedule-id [in ids]})))

(defn get-schedule-by-name
  "Gets a schedule by name if one exists otherwise returns nil"
  [nm]
  (first (select schedule (where {:schedule-name nm}))))

(defn ls-node-schedules []
  (select node-schedule))

(defn nodes-for-schedule
  "Lookup nodes tied to a schedule"
  [schedule-id]
  (exec-raw ["select
                     ns.node_schedule_id
                   , ns.node_id
                   , n.node_type_id
                   , s.cron_expression
                from
                     node_schedule ns
                join schedule     s
                  on ns.schedule_id = s.schedule_id
                join node n
                  on ns.node_id = n.node_id
               where ns.schedule_id = ?"
             [schedule-id]]
            :results))


;-----------------------------------------------------------------------
; Alert fns
;-----------------------------------------------------------------------
(defn insert-alert! [m user-id]
  (let [ts (util/now)
        merged-map (merge (dissoc m :alert-id) {:creator-id user-id :updater-id user-id
                                                :create-ts ts :update-ts ts})]
    (log/info "Creating new alert: " merged-map)
    (-> (insert alert (values merged-map))
         extract-identity)))


;-----------------------------------------------------------------------
; Update an existing alert
; Answers with the alert-id if update is successful.
;-----------------------------------------------------------------------
(defn update-alert! [{:keys [alert-id] :as m} user-id]
  (let [merged-map (merge m {:updater-id user-id :update-ts (util/now)})]
    (log/info "Updating alert: " m)
    (update alert (set-fields (dissoc m :alert-id))
      (where {:alert-id alert-id})))
  alert-id)

(defn rm-alert! [alert-id]
  (delete alert (where {:alert-id alert-id})))

(defn ls-alerts
  []
  "Lists all alerts"
  (select alert))

(defn get-alert
  "Gets a alert for the id specified"
  [id]
  (first (select alert
          (where {:alert-id id}))))

(defn get-alerts [ids]
  (select alert
    (where {:alert-id [in ids]})))

(defn get-alert-by-name
  "Gets a alert by name if one exists otherwise returns nil"
  [nm]
  (first (select alert (where {:alert-name nm}))))

(defn ls-node-alerts []
  (select node-alert))

(defn nodes-for-alert
  "Lookup nodes tied to a alert"
  [alert-id]
  (exec-raw ["select
                     ns.node_alert_id
                   , ns.node_id
                   , n.node_type_id
                   , s.cron_expression
                from
                     node_alert ns
                join alert     s
                  on ns.alert_id = s.alert_id
                join node n
                  on ns.node_id = n.node_id
               where ns.alert_id = ?"
             [alert-id]]
            :results))


;-----------------------------------------------------------------------
; Alert ids associated with the specified node id.
;-----------------------------------------------------------------------
(defn alerts-for-node [node-id]
  (select node-alert (where {:node-id node-id})))

(defn alert-ids-for-node [node-id]
  (->> node-id
       alerts-for-node
       (map :alert-id)
       set))

;-----------------------------------------------------------------------
; Job alert ids associated with the specified node id.
;-----------------------------------------------------------------------

(defn node-alerts-for-node [node-id]
  (select node-alert (where {:node-id node-id})))

(defn node-alert-ids-for-node [node-id]
  (->> node-id
       node-alerts-for-node
       (map :node-alert-id)
       set))


;-----------------------------------------------------------------------
; Deletes from node-alert all rows matching node-alert-ids
; Also removes from quartz all jobs matching the node-alert-id
; ie the triggers IDd by node-alertr-id
;-----------------------------------------------------------------------
(defn rm-node-alerts! [node-alert-ids]
  (delete node-alert (where {:node-alert-id [in node-alert-ids]})))

;-----------------------------------------------------------------------
; Deletes from node-alert all rows matching node-id
;-----------------------------------------------------------------------
(defn rm-alerts-for-node! [node-id]
  (-> node-id node-alerts-for-node rm-node-alerts!))

(defn get-node-alert-info [node-id]
  (exec-raw ["select   ns.node_alert_id
                     , s.*
                from   node_alert ns
                join   alert s
                  on   ns.alert_id = s.alert_id
               where   node_id = ?" [node-id]] :results))



;-----------------------------------------------------------------------
; Associates a job to a set of alert-ids.
; alert-ids is a set of integer ids
;-----------------------------------------------------------------------
(defn assoc-alerts!
  ([{:keys [node-id alert-ids]} user-id]
    (assoc-alerts! node-id alert-ids user-id))

  ([node-id alert-ids user-id]
    (let [alert-id-set (set alert-ids)
          data {:node-id node-id :creator-id user-id :create-ts (util/now)}
          insert-maps (map #(assoc %1 :alert-id %2) (repeat data) alert-id-set)]
      (if (not (empty? insert-maps))
        (insert node-alert (values insert-maps))))))


(defn insert-node-alert! [node-id alert-id user-id]
  (let [data {:node-id node-id
              :alert-id alert-id
              :creator-id user-id
              :create-ts (util/now)}]

    (-> (insert node-alert (values data))
        extract-identity)))

(defn rm-node-alert! [node-alert-id]
  (delete node-alert (where {:node-alert-id node-alert-id})))

(defn get-node-alert [node-alert-id]
  (-> (select node-alert (where {:node-alert-id node-alert-id})) first))

(def ^:private node-alert-assoc-sql "
select
       ns.node_alert_id
     , a.alert_id
     , a.alert_name
  from node_alert ns
  join alert      a
    on ns.alert_id = a.alert_id
 where ns.node_id = ?
")

(defn node-alert-associations
  "Answers with alert id, alert name, node alert id"
  [node-id]
  (exec-raw [node-alert-assoc-sql [node-id]] :results))


;-----------------------------------------------------------------------
; Insert a node. Answer with the inserted node's row id
;-----------------------------------------------------------------------
(defn- insert-node! [type-id node-nm node-desc enabled? dir-id user-id]
  (let [ts (util/now)
        data {:node-name node-nm
              :node-desc node-desc
              :node-type-id type-id
              :is-enabled   enabled?
              :is-system false           ; system nodes should just be added via sql
              :node-directory-id dir-id
              :creator-id user-id
              :updater-id user-id
              :create-ts ts
              :update-ts ts}]
    (-> (insert node (values data))
        extract-identity)))

(defn- update-node! [node-id node-nm node-desc enabled? dir-id user-id]
  (let [data {:node-name node-nm
              :node-desc node-desc
              :is-enabled enabled?
              :node-directory-id dir-id
              :updater-id user-id
              :update-ts (util/now)}]
    (update node (set-fields data)
      (where {:node-id node-id}))
    node-id))


(defn change-node-owning-directory
  [node-id new-parent-dir-id]
  (update node (set-fields {:node-directory-id new-parent-dir-id})
          (where {:node-id node-id}))
  node-id)


(def insert-job-node! (partial insert-node! data/job-type-id))
(def insert-workflow-node! (partial insert-node! data/workflow-type-id))


(def ^:private job-tbl-fields [:execution-directory :command-line :max-concurrent :max-retries :agent-id])
;-----------------------------------------------------------------------
; Insert a job. Answers with the inserted job's row id.
;-----------------------------------------------------------------------
(defn- insert-job! [{:keys [job-id job-name job-desc is-enabled node-directory-id] :as m} user-id]
  (transaction
    (let [node-id (insert-job-node! job-name job-desc is-enabled node-directory-id user-id)
          data (assoc (select-keys m job-tbl-fields) :job-id node-id)]
      (insert job (values data))
      node-id)))


;-----------------------------------------------------------------------
; Update an existing job.
; Answers with the job-id if update is successful.
;-----------------------------------------------------------------------
(defn- update-job! [{:keys [job-id job-name job-desc is-enabled node-directory-id] :as m} user-id]
  (let [data (select-keys m job-tbl-fields)]
    (transaction
      (update-node! job-id job-name job-desc is-enabled node-directory-id user-id)
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
  (let [{:keys [workflow-name workflow-desc is-enabled node-directory-id]} m
        node-id (insert-workflow-node! workflow-name workflow-desc is-enabled node-directory-id user-id)]
    (insert workflow (values {:workflow-id node-id}))
    node-id))

(defn- update-workflow! [{:keys [workflow-id workflow-name workflow-desc is-enabled node-directory-id] :as m} user-id]
  (update-node! workflow-id workflow-name workflow-desc is-enabled node-directory-id user-id)
  workflow-id)

(defn save-workflow
  "This must be called from within a korma transaction. This doesn't set one up
   because workflow dependcy graph also needs to be saved at the same time."
  [{:keys [workflow-id] :as w} user-id]
  (if (id? workflow-id)
    (update-workflow! w user-id)
    (insert-workflow! w user-id)))


;-----------------------------------------------------------------------
; Schedule ids associated with the specified node id.
;-----------------------------------------------------------------------
(defn schedules-for-node [node-id]
  (select node-schedule (where {:node-id node-id})))

(defn schedule-ids-for-node [node-id]
  (->> node-id
       schedules-for-node
       (map :schedule-id)
       set))

(def ^:private node-schedule-assoc-sql "
select
       ns.node_schedule_id
     , s.schedule_id
     , s.schedule_name
  from node_schedule ns
  join schedule      s
    on ns.schedule_id = s.schedule_id
 where ns.node_id = ?
")

(defn node-schedule-associations
  "Answers with schedule id, schedule name, node schedule id"
  [node-id]
  (exec-raw [node-schedule-assoc-sql [node-id]] :results))

;-----------------------------------------------------------------------
; Job schedule ids associated with the specified node id.
;-----------------------------------------------------------------------

(defn node-schedules-for-node [node-id]
  (select node-schedule (where {:node-id node-id})))

(defn node-schedule-ids-for-node [node-id]
  (->> node-id
       node-schedules-for-node
       (map :node-schedule-id)
       set))


;-----------------------------------------------------------------------
; Deletes from node-schedule all rows matching node-schedule-ids
; Also removes from quartz all jobs matching the node-schedule-id
; ie the triggers IDd by node-scheduler-id
;-----------------------------------------------------------------------
(defn rm-node-schedules! [node-schedule-ids]
  (delete node-schedule (where {:node-schedule-id [in node-schedule-ids]})))

;-----------------------------------------------------------------------
; Deletes from node-schedule all rows matching node-id
;-----------------------------------------------------------------------
(defn rm-schedules-for-node! [node-id]
  (-> node-id node-schedules-for-node rm-node-schedules!))

(defn get-node-schedule-info [node-id]
  (exec-raw ["select   ns.node_schedule_id
                     , s.*
                from   node_schedule ns
                join   schedule s
                  on   ns.schedule_id = s.schedule_id
               where   node_id = ?" [node-id]] :results))


(defn insert-node-schedule! [node-id schedule-id user-id]
  (let [data {:node-id node-id
              :schedule-id schedule-id
              :creator-id user-id
              :create-ts (util/now)}]

    (-> (insert node-schedule (values data))
        extract-identity)))

(defn rm-node-schedule! [node-schedule-id]
  (delete node-schedule (where {:node-schedule-id node-schedule-id})))

(defn get-node-schedule [node-schedule-id]
  (-> (select node-schedule (where {:node-schedule-id node-schedule-id})) first))

;-----------------------------------------------------------------------
; Associates a job to a set of schedule-ids.
; schedule-ids is a set of integer ids
;-----------------------------------------------------------------------
(defn assoc-schedules!
  ([{:keys [node-id schedule-ids]} user-id]
    (assoc-schedules! node-id schedule-ids user-id))

  ([node-id schedule-ids user-id]
    (let [schedule-id-set (set schedule-ids)
          data {:node-id node-id :creator-id user-id :create-ts (util/now)}
          insert-maps (map #(assoc %1 :schedule-id %2) (repeat data) schedule-id-set)]
      (if (not (empty? insert-maps))
        (insert node-schedule (values insert-maps))))))

;-----------------------------------------------------------------------
; Gets all enabled nodes with schedules.
;-----------------------------------------------------------------------
(defn enabled-nodes-schedule-info []
  (exec-raw ["select
                     ns.node_schedule_id
                   , ns.node_id
                   , n.node_type_id
                   , s.cron_expression
                from
                     node_schedule ns
                join schedule     s
                  on ns.schedule_id = s.schedule_id
                join node         n
                  on ns.node_id = n.node_id
               where n.is_enabled = 1"]
            :results))



;-----------------------------------------------------------------------
; Workflow vertices and edges
;-----------------------------------------------------------------------
(defn- rm-workflow-edge [workflow-id]
  "Deletes workflow edges for the workflow id"
  (exec-raw ["delete
                from workflow_edge
               where exists (select 1
                               from workflow_vertex v
                              where v.workflow_id = ?
                                and workflow_edge.vertex_id = v.workflow_vertex_id)"
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
  left join workflow_edge  e
         on fv.workflow_vertex_id = e.vertex_id
  left join workflow_vertex tv
         on e.next_vertex_id = tv.workflow_vertex_id
       join node fn
         on fv.node_id = fn.node_id
  left join node tn
         on tv.node_id = tn.node_id
      where fv.workflow_id = ? 
        and (fv.workflow_id = tv.workflow_id or tv.workflow_id is null) " [id]] :results))


;-----------------------------------------------------------------------
; Job execution stuff should be in another place likely
; schedule-ids is a set of integer ids
;-----------------------------------------------------------------------

(defentity execution
  (pk :execution-id)
  (entity-fields :status-id :start-ts :finish-ts))

(defentity execution-workflow
  (pk :execution-workflow-id)
  (entity-fields :execution-id :workflow-id :root :status-id :start-ts :finish-ts))

(defentity execution-vertex
  (pk :execution-vertex-id)
  (entity-fields :execution-id :execution-workflow-id :node-id :runs-execution-workflow-id :status-id :start-ts :finish-ts))

(defentity execution-edge
  (pk :execution-edge-id)
  (entity-fields :execution-edge-id :execution-id :vertex-id :next-vertex-id :success))

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

(defn- children-workflows-cte
  "For the wf-id specified recursively get all workflows it uses.
   Answers with a seq of ints."
  [wf-id]
  (let [q (string/replace child-workflow-sql #"<wf-id>" (str wf-id))]
    (->> (exec-raw [q []] :results)
         (map :workflow-id)
         doall)))

(def ^:private non-cte-child-wfs-sql
"
select w.workflow_id
  from workflow_vertex wv
  join workflow        w
    on wv.node_id = w.workflow_id
 where wv.workflow_id in (<ids>) ")

(defn- children-workflows-non-cte
  [wf-id]
  (loop [ids [wf-id] ans #{wf-id}]
    (let [ids-csv (string/join "," ids)
          q (string/replace non-cte-child-wfs-sql #"<ids>" ids-csv)
          next-ids (->> (exec-raw [q []] :results)
                        (map :workflow-id)
                        doall)]

      (if (seq next-ids)
        (recur next-ids (into ans next-ids))
        ans))))

(defn- children-workflows
  [wf-id]
  (if (conf/db-cte-support?)
    (children-workflows-cte wf-id)
    (children-workflows-non-cte wf-id)))

(defn- insert-execution-workflows
  "Creates rows in execution-workflows table and returns a map of
   wf-id to execution-workflow-id."
  [exec-id wf child-wfs]
  (log/debug "insert execution-workflows: exec-id: " exec-id
         ", wf: " wf ", child-wfs: " child-wfs)

  (reduce (fn [ans id]
            (->> (insert execution-workflow
                   (values {:execution-id exec-id
                            :workflow-id id
                            :root (= id wf)
                            :status-id data/unexecuted-status}))
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
        where ew.execution_id = ? " [data/unexecuted-status exec-id]])

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
        (values {:status-id data/started-status
                 :start-ts  (util/now)}))
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
        id-map  (insert-execution-workflows exec-id data/synthetic-workflow-id [])
        exec-wf-id (id-map data/synthetic-workflow-id)
        exec-vertex-id (snapshot-synthetic-workflow exec-id exec-wf-id job-id data/unexecuted-status)
        job-name (get-job-name job-id)]

    {:execution-id exec-id
     :wf-id data/synthetic-workflow-id
     :exec-wf-id exec-wf-id
     :exec-vertex-id exec-vertex-id
     :status data/unexecuted-status
     :job-nm job-name
     :job-id job-id
     :node-type data/job-type-id}))


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
   left join execution_edge     e
          on f.execution_vertex_id = e.vertex_id
   left join execution_vertex t
          on e.next_vertex_id = t.execution_vertex_id
        join node fn
          on f.node_id = fn.node_id
   left join node tn
          on t.node_id = tn.node_id
       where ew.execution_id = ? " [id]] :results))


(defn execution-aborted [exec-id ts]
  ; all vertices which are not finished and status id is not 1
  ; all execution workflows which are not finished and status id is not 1
  ; execution tbl
  (transaction
   (update execution-vertex
           (set-fields {:status-id data/aborted-status :finish-ts ts})
           (where (and (= :execution-id exec-id)
                       (not= :start-ts nil)
                       (= :finish-ts nil))))

   (update execution-workflow
           (set-fields {:status-id data/aborted-status :finish-ts ts})
           (where (and (= :execution-id exec-id)
                       (not= :start-ts nil)
                       (= :finish-ts nil))))

   (update execution
           (set-fields {:status-id data/aborted-status :finish-ts ts})
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
                           (if success? data/finished-success data/finished-error)
                           finish-ts))


(defn execution-vertex-started [exec-vertex-id ts]
  (update execution-vertex
    (set-fields {:status-id data/started-status :start-ts ts})
    (where {:execution-vertex-id exec-vertex-id})))



(defn update-execution-vertices-status [ids status-id ts]
  (update execution-vertex
    (set-fields {:status-id status-id :finish-ts ts})
    (where (in :execution-vertex-id ids))))

(defn execution-vertex-finished [exec-vertex-id status-id ts]
  (update-execution-vertices-status [exec-vertex-id] status-id ts))

(defn workflow-finished
  "Marks the workflow as finished and sets the status in both the execution-workflow
   table and in execution-vertex."
  [exec-wf-id success? finish-ts]
  (let [status (if success? data/finished-success data/finished-error)]
    (update execution-workflow
      (set-fields {:status-id status :finish-ts finish-ts})
      (where {:execution-workflow-id exec-wf-id}))))

(defn workflow-started
  "Marks the workflow as finished and sets the status in both the
   execution-workflow and execution-vertex tables."
  [exec-wf-id start-ts]
  (update execution-workflow
    (set-fields {:status-id data/started-status :start-ts start-ts})
    (where {:execution-workflow-id exec-wf-id})))


(defn workflows-and-vertices-finished
  "Marks the workflow and the execution vertex which represents the
   workflow as finished with the supplied status."
  [exec-vertex-ids exec-wf-ids success? ts]
  (let [status-id (if success? data/finished-success data/finished-error)]
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




(def ^:private is-synthetic-wf-sql
  "select 1
     from execution e
     join execution_workflow ew
       on e.execution_id = ew.execution_id
    where ew.root = true
      and e.execution_id = ?
      and ew.workflow_id = ? ")

(defn synthetic-workflow-execution? [exec-id]
  (-> (exec-raw [is-synthetic-wf-sql [exec-id data/synthetic-workflow-id]] :results)
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




;-----------------------------------------------------------------------
;              EXECUTION SEARCH
;             ------------------
; korma joins produce inefficient sql, nested inline views
; which at least h2 doesn't like, probably other rdbms also?
;-----------------------------------------------------------------------
(def ^:private wf-execution-search-sql
  "  select
         e.execution_id
       , nn.node_name as execution_name
       , e.status_id
       , e.start_ts
       , e.finish_ts
    from execution e
    join execution_workflow ew
      on e.execution_id = ew.execution_id
     and ew.root = true
    join node nn
      on ew.workflow_id = nn.node_id
     and nn.is_system = false
   where 1 = 1 \n")

; korma joins produce inefficient sql, nested inline views
; which at least h2 doesn't like, probably other rdbms also?
(def ^:private job-execution-search-sql
 " select
         e.execution_id
       , nn.node_name as execution_name
       , e.status_id
       , e.start_ts
       , e.finish_ts
    from execution e
    join execution_workflow ew
      on e.execution_id = ew.execution_id
     and ew.root = true
    join node wf
      on ew.workflow_id = wf.node_id
     and wf.is_system = true
    join execution_vertex v
      on ew.execution_workflow_id = v.execution_workflow_id
    join node nn
      on v.node_id = nn.node_id
   where 1 = 1 \n")


(defn- make-in-clause [xs]
  (if (-> xs count zero?)
    ""
    (let [csv (apply str (interpose "," xs))]
      (assert (every? (partial instance? Number) xs)
              "Only making in clauses for Numbers.")
      (str " in (" csv ")"))))

(defn- make-like-clause [x]
  (if (and x (-> x empty? not))
    (str " like '%" x "%'")
    ""))

; FIXME: this is terrible code
; execution-id is an int/long
; node-name is a string
; start-ts finish-ts are java.sql.Timestamp instances
; status-ids is a seq of ints
(defn execution-search
  ([execution-id] (execution-search execution-id nil nil nil nil))
  ([execution-id node-name start-ts finish-ts status-ids]
   (let [id-c " and e.execution_id = ? \n"
         like-clause (make-like-clause node-name)
         name-c (if (empty? like-clause)
                  ""
                  (str " and nn.node_name " like-clause "\n"))
         start-c " and e.start_ts >= ? \n"
         finish-c " and e.finish_ts < ? \n"
         in-clause (make-in-clause status-ids)
         status-c (if (empty? in-clause)
                    ""
                    (str " and e.status_id " in-clause " \n"))
         tuples [[execution-id id-c] [start-ts start-c] [finish-ts finish-c]]
         tt (filter (fn[[v c :as t]] (-> v nil? not)) tuples)
         vv (map first tt)
         cc (apply str (-> (map second tt) (conj status-c name-c)))
         full-query (str wf-execution-search-sql
                         cc
                         " union all \n"
                         job-execution-search-sql
                         cc)
         params (vec (flatten (repeat 2 vv)))]
     (exec-raw [full-query params] :results))))


(defn get-execution-name [exec-id]
  (->> (execution-search exec-id)
       (map :execution-name)
       first))



;-----------------------------------------------------------------------
; Agent 
;-----------------------------------------------------------------------
(defn insert-agent! [m user-id]
  (let [ts (util/now)
        merged-map (merge (dissoc m :agent-id) {:creator-id user-id :updater-id user-id
                                                :create-ts ts :update-ts ts})]
    (log/info "Creating new agent: " merged-map)
    (-> (insert agent (values merged-map))
         extract-identity)))

(defn update-agent! [{:keys [agent-id] :as m} user-id]
  (let [merged-map (merge m {:updater-id user-id :update-ts (util/now)})]
    (log/info "Updating agent: " m)
    (update agent (set-fields (dissoc m :agent-id))
      (where {:agent-id agent-id})))
  agent-id)

(defn rm-agent! [agent-id]
  (delete agent (where {:agent-id agent-id})))

(defn ls-agents
  []
  "Lists all agents"
  (select agent))

(defn get-agent
  "Gets a agent for the id specified"
  [id]
  (first (select agent
          (where {:agent-id id}))))

(defn get-agents [ids]
  (select agent
    (where {:agent-id [in ids]})))

(defn get-agent-by-name
  "Gets a agent by name if one exists otherwise returns nil"
  [nm]
  (first (select agent (where {:agent-name nm}))))


;;----------------------------------------------------------------------
;; Explorer (Dir tree) actions
;;----------------------------------------------------------------------
(defn directory-exists?
  "Answers if the directory exists with parent directory id specified."
  [dir-name parent-dir-id]
  (let [conds {:node-directory-name dir-name :parent-directory-id parent-dir-id}]
    (-> (select node-directory (where conds)) count zero? not)))

(defn- update-directory
  "Updates the directory with id dir-id to the name specified by
   dir-name and the parent directory to be parent-dir-id"
  [dir-id dir-name parent-dir-id]
  (update node-directory (set-fields {:node-directory-name dir-name :parent-directory-id parent-dir-id})
          (where {:node-directory-id dir-id}))
  dir-id)

(defn- insert-directory
  "Creates a new directory and returns the newly created directory's id."
  [dir-name parent-dir-id]
  (-> (insert node-directory (values {:node-directory-name dir-name :parent-directory-id parent-dir-id}))
      extract-identity))

(defn save-directory
  "Saves the directory either creating a new row or updating the existing.
   Answers with the dir-id of the new row or the row that was updated."
  [dir-id dir-name parent-dir-id]
  (if (id? dir-id)
    (update-directory dir-id dir-name parent-dir-id)
    (insert-directory dir-name parent-dir-id)))

(defn rm-directory
  [dir-id]
  (delete node-directory (where {:node-directory-id dir-id})))

(defn change-directory-parent
  "Changes a directory's parent directory id"
  [dir-id parent-dir-id]
  (update node-directory (set-fields {:parent-directory-id parent-dir-id})
          (where {:node-directory-id dir-id}))
  dir-id)


(defn get-directory-by-id
  [id]
  (first (select node-directory (where {:node-directory-id id}))))


(def ^:private empty-directory-sql "
select count(1) as i
  from node_directory nd
 where nd.parent_directory_id = ?
union all
select count(1) as i
 from node n
where n.node_directory_id = ?
")

(defn empty-directory? [dir-id]
  (let [data (exec-raw [empty-directory-sql [dir-id dir-id]] :results)]
    (->> data
         (map :i)
         (reduce +)
         zero?)))

;;----------------------------------------------------------------------
;; Node Deletions
;;----------------------------------------------------------------------
(defn rm-executions!
  "Deletes all data for the execution ids  from the various execution_* tables."
  [execution-ids]
  (transaction
   (doseq [exec-id execution-ids
           :let [conds {:execution-id exec-id}]]
     (doseq [tbl [execution-edge execution-vertex execution-workflow execution]]
       (delete tbl (where conds))))))

(def ^:private exec-ids-for-node-sql "
select
       distinct execution_id
  from execution_workflow
 where workflow_id = ?
union
select
       distinct execution_id
  from execution_vertex
 where node_id = ? ")

(defn- execution-ids-for-node [node-id]
  (->> (exec-raw [exec-ids-for-node-sql [node-id node-id]] :results)
       (map :execution-id)))

(def ^:private workflow-ref-node-sql "
select
       wf.node_name as workflow_name
  from
       node wf
  join workflow_vertex wv
    on wf.node_id = wv.workflow_id
 where
       wv.node_id = ? ")

(defn workflows-referencing-node
  "Answers with a list of workflow names referencing node-id."
  [node-id]
  (->> (exec-raw [workflow-ref-node-sql [node-id]] :results)
       (map :workflow-name)))


(defn rm-node!
  "Deletes the node specified by node-id and returns the raw node data."
  [node-id]
  (assert (-> node-id workflows-referencing-node seq not) (str "workflow references exist for node-id: " node-id))
  (transaction
   (let [{:keys [node-type-id] :as data} (get-node-by-id node-id)]

    ;; remove executions
    (-> node-id execution-ids-for-node rm-executions!) 

    ;; remove from actual subtype tables
    (if (util/job-type? node-type-id)
      (delete job (where {:job-id node-id}))
      (do
        (delete workflow-vertex (where {:workflow-id node-id}))
        (delete workflow (where {:workflow-id node-id}))))

    ;; remove from node
    (delete node (where {:node-id node-id}))
    data)))


;; gets the directories which are children of directory with id x
(def ^:private explorer-sql "
   select
          'directory'                           as element_type
        , nd.node_directory_id                  as element_id
        , nd.node_directory_name                as element_name
        , count(n.node_id) + coalesce(wc.cnt,0) as num_children
     from
          node_directory nd
left join (select nd.node_directory_id, 1 as cnt
             from
                  node_directory nd
            where nd.parent_directory_id = <dir-id> 
              and exists (select 1
                            from node_directory x
                           where x.parent_directory_id = nd.node_directory_id)) wc -- dirs with children directories
       on nd.node_directory_id = wc.node_directory_id
left join node           n
       on nd.node_directory_id = n.node_directory_id
    where nd.parent_directory_id =  <dir-id>
 group by nd.node_directory_id
union all
   select
          nt.node_type_name as element_type
        , n.node_id         as element_id
        , n.node_name       as element_name
        , 0                 as num_children
     from node              n
     join node_type         nt
       on n.node_type_id = nt.node_type_id
    where n.node_directory_id = <dir-id> 
      and n.is_system = false
")

(defn explorer-info
  "Retrieves info for children nodes"
  [dir-id]
  (let [q (string/replace explorer-sql #"<dir-id>" (str dir-id))]
    (exec-raw [q []] :results)))


(def ^:private node-ref-alert-sql "
select n.node_name
  from node_alert na
  join node       n
    on na.node_id = n.node_id
 where na.alert_id = <id>
")

(defn nodes-referencing-alert
  "Answers with a list of node names referencing alert-id."
  [alert-id]
  (let [q (string/replace node-ref-alert-sql #"<id>" (str alert-id))]
    (map :node-name (exec-raw [q []] :results))))

(def ^:private node-ref-schedule-sql "
select n.node_name
  from node_schedule ns
  join node       n
    on ns.node_id = n.node_id
 where ns.schedule_id = <id>
")

(defn nodes-referencing-schedule
  "Answers with a list of node names referencing schedule-id."
  [schedule-id]
  (let [q (string/replace node-ref-schedule-sql #"<id>" (str schedule-id))]
    (map :node-name (exec-raw [q []] :results))))

(def ^:private job-ref-agent-sql "
select n.node_name
  from job  j
  join node n
    on j.job_id = n.node_id
 where j.agent_id = <id>
")

(defn jobs-referencing-agent
  "Answers with a list of job names referencing agent-id."
  [agent-id]
  (let [q (string/replace job-ref-agent-sql #"<id>" (str agent-id))]
    (exec-raw [q []] :results)))


(def ^:private execution-vertex-alert-sql "
select
       ev.execution_vertex_id
     , na.alert_id
  from execution_vertex ev
  join node_alert       na
    on ev.node_id = na.node_id
 where ev.execution_id = <id>
")

(defn execution-vertices-with-alerts
  "Answers with a map of execution-vertex-id => #{alert-ids}"
  [execution-id]
  (let [q (string/replace execution-vertex-alert-sql #"<id>" (str execution-id))
        rs (exec-raw [q []] :results)]
    (reduce (fn [m {:keys [execution-vertex-id alert-id]}]
              (if (m execution-vertex-id)
                (update-in m [execution-vertex-id] conj alert-id)
                (assoc m execution-vertex-id #{alert-id})))
            {}
            rs)))


(def ^:private execution-root-wf "
select
       ew.workflow_id
  from execution_workflow ew
 where ew.execution_id = <id>
   and ew.root = true
")

(defn get-execution-root-wf [execution-id]
  (let [q (string/replace execution-vertex-alert-sql #"<id>" (str execution-id))
        rs (exec-raw [q []] :results)]
    (-> rs first :workflow-id)))

;; gets all jobs and workflows tied to a schedule and the last
;; execution id status and finish ts if run
(def ^:private dashboard-nodes-sql "
   select n.node_id
        , n.node_name
        , n.node_type_id
        , n.is_enabled
        , ex.execution_id
        , ex.status_id
        , ex.finish_ts
     from node          n
     join workflow      wf
       on n.node_id = wf.workflow_id
left join ( select
                   ew.workflow_id
                 , max(ew.execution_id) as last_execution_id
              from execution_workflow ew
             where ew.root = 1
               and ew.workflow_id != 1
          group by ew.workflow_id) last_exec
       on n.node_id = last_exec.workflow_id
left join execution ex
       on last_exec.last_execution_id = ex.execution_id
    where exists (select 1
                    from node_schedule ns
                   where n.node_id = ns.node_id)
union all
   select n.node_id
        , n.node_name
        , n.node_type_id
        , n.is_enabled
        , ex.execution_id
        , ex.status_id
        , ex.finish_ts
     from node          n
     join job           j
       on n.node_id = j.job_id
left join ( select
                   ev.node_id
                 , max(ew.execution_id) as last_execution_id
              from execution_workflow ew
              join execution_vertex   ev
                on ew.execution_id = ev.execution_id
             where ew.root = 1
               and ew.workflow_id = 1
          group by ev.node_id) last_exec
       on n.node_id = last_exec.node_id
left join execution ex
       on last_exec.last_execution_id = ex.execution_id
    where exists (select 1
                    from node_schedule ns
                   where n.node_id = ns.node_id)
")


(defn ls-dashboard-elements
  []
  (exec-raw [dashboard-nodes-sql []] :results))


(defn set-node-enabled
  "Marks the node as enabled or disabled."
  [node-id enabled?]
  (update node (set-fields {:is-enabled enabled? :update-ts (util/now)})
          (where {:node-id node-id})))
