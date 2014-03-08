(ns jsk.conductor.cache
  "Cache of jobs and schedules"
  (:refer-clojure :exclude [agent])
  (:require [jsk.common.util :as util]))

(defn new-cache [] {:agents {}
                    :alerts {}
                    :jobs {}
                    :workflows {}
                    :schedules {}
                    :node-schedules {} })

;-----------------------------------------------------------------------
; Agents
;-----------------------------------------------------------------------
(defn put-agent
  "Puts agent in cache c."
  [c {:keys [agent-id] :as agent}]
    (assoc-in c [:agents agent-id] agent))

(defn put-agents
  "Puts agents in the collection nn in cache c."
  [c nn]
  (reduce #(put-agent %1 %2) c nn))

(defn agent
  "Gets the agent for the id. Can return nil if agent-id is unknown."
  [c agent-id]
  (get-in c [:agents agent-id]))

(defn agents
  "Gets all agents in cache c."
  [c]
  (-> c :agents vals))

(defn rm-agent
  "Removes the agent specified by the agent-id."
  [c agent-id]
  (update-in c [:agents] dissoc agent-id))

;-----------------------------------------------------------------------
; Alerts
;-----------------------------------------------------------------------
(defn put-alert
  "Puts alert in cache c."
  [c {:keys [alert-id] :as alert}]
    (assoc-in c [:alerts alert-id] alert))

(defn put-alerts
  "Puts alerts in the collection nn in cache c."
  [c nn]
  (reduce #(put-alert %1 %2) c nn))

(defn alert
  "Gets the alert for the id. Can return nil if alert-id is unknown."
  [c alert-id]
  (get-in c [:alerts alert-id]))

(defn alerts
  "Gets all alerts in cache c."
  [c]
  (-> c :alerts vals))

(defn rm-alert
  "Removes the alert specified by the alert-id."
  [c alert-id]
  (update-in c [:alerts] dissoc alert-id))

;-----------------------------------------------------------------------
; Jobs
;-----------------------------------------------------------------------
(defn put-job
  "Puts job in cache c."
  [c {:keys [job-id] :as job}]
    (assoc-in c [:jobs job-id] job))

(defn put-jobs
  "Puts jobs in the collection nn in cache c."
  [c nn]
  (reduce #(put-job %1 %2) c nn))

(defn job
  "Gets the job for the id. Can return nil if job-id is unknown."
  [c job-id]
  (get-in c [:jobs job-id]))

(defn jobs
  "Gets all jobs in cache c."
  [c]
  (-> c :jobs vals))

(defn rm-job
  "Removes the job specified by the job-id."
  [c job-id]
  (update-in c [:jobs] dissoc job-id))

;-----------------------------------------------------------------------
; Workflows
;-----------------------------------------------------------------------
(defn put-workflow
  "Puts workflow in cache c."
  [c {:keys [workflow-id] :as workflow}]
    (assoc-in c [:workflows workflow-id] workflow))

(defn put-workflows
  "Puts workflows in the collection nn in cache c."
  [c nn]
  (reduce #(put-workflow %1 %2) c nn))

(defn workflow
  "Gets the workflow for the id. Can return nil if workflow-id is unknown."
  [c workflow-id]
  (get-in c [:workflows workflow-id]))

(defn workflows
  "Gets all workflows in cache c."
  [c]
  (-> c :workflows vals))

(defn rm-workflow
  "Removes the workflow specified by the workflow-id."
  [c workflow-id]
  (update-in c [:workflows] dissoc workflow-id))

;-----------------------------------------------------------------------
; Schedules
;-----------------------------------------------------------------------
(defn put-schedule
  "Puts schedule in cache c" 
  [c {:keys [schedule-id] :as schedule}]
  (assoc-in c [:schedules schedule-id] schedule))

(defn put-schedules
  "Puts all schedule in collection ss in cache c" 
  [c ss]
  (reduce #(put-schedule %1 %2) c ss))

(defn schedule
  "Gets the schedule by id or returns nil."
  [c schedule-id]
  (get-in c [:schedules schedule-id]))

(defn schedule-cron-expr
  "Gets the cron expression associated with the schedule"
  [c schedule-id]
  (:cron-expression (schedule c schedule-id)))

(defn schedules
  "Gets all schedules."
  [c]
  (-> c :schedules vals))

(defn rm-schedule
  "Removes the schedule by id"
  [c schedule-id]
  (update-in c [:schedules] dissoc schedule-id))



;-----------------------------------------------------------------------
; Nodes Schedule associations
;-----------------------------------------------------------------------
(defn put-schedule-assoc
  "Puts the node-sched association in to cache c."
  [c {:keys [node-schedule-id] :as node-sched}]
  (assoc-in c [:node-schedules node-schedule-id] node-sched))

(defn put-schedule-assocs
  "Puts all associations in node-schedule-associations in to cache c."
  [c node-schedule-associations]
  (reduce #(put-schedule-assoc %1 %2) c node-schedule-associations))

(defn schedule-assoc
  "Retrieves the node schedule association for the id or nil."
  [c node-schedule-id]
  (get-in c [:node-schedules node-schedule-id]))

(defn schedule-assocs
  "Retrieves all node schedule association."
  [c]
  (-> c :node-schedules vals))

(defn- assoc-cron-expr
  "Associates cron expression for each node-schedule-assoc"
  [c node-sched-assocs]
  (map (fn [{:keys [schedule-id] :as ns-assoc}]
         (merge ns-assoc (-> (schedule c schedule-id)
                             (select-keys [:cron-expression]))))
         node-sched-assocs))

(defn enabled-node-ids
  "Answers with a set of node ids (job/workflow-ids) of nodes which are enabled."
  [c]
  (let [filter-fn (fn [{:keys [is-enabled]}]
                    is-enabled)
        id-fn (fn [node-fn node-id-kw]
                (->> c node-fn (filter filter-fn) (map node-id-kw)))
        job-ids (id-fn jobs :job-id)
        wf-ids (id-fn workflows :wf-id)]
    (set (concat job-ids wf-ids))))


(defn schedule-assocs-with-cron-expr
  "Retrieves all node schedule association with the associated :cron-expression.
   For enabled jobs/workflows only."
  [c]
  (let [enabled-node-ids (enabled-node-ids c)
        node-schedules (filter (fn [{:keys [node-id]}]
                                 (contains? enabled-node-ids node-id))
                               (schedule-assocs c))]
    (assoc-cron-expr c node-schedules)))


(defn rm-schedule-assoc
  "Removes the association pointed to by node-schedule-id."
  [c node-schedule-id]
  (update-in c [:node-schedules] dissoc node-schedule-id))

(defn rm-schedule-assocs
  "Removes all associations pointed to by node-schedule-ids."
  [c node-schedule-ids]
  (reduce #(rm-schedule-assoc %1 %2) c node-schedule-ids))

;-----------------------------------------------------------------------
; Additional query functions
;-----------------------------------------------------------------------
(defn- filter-schedule-assocs
  "Filters schedule associations based on predicate fn pred.
   pred is a function which takes a node-schedule-assoc map and returns true or false."
  [c pred]
  (->> c :node-schedules vals (filter pred)))

(defn schedule-assocs-for-node
  "Retrieves all schedule associations for the given node id"
  [c node-id]
  (filter-schedule-assocs c #(= node-id (:node-id %1))))

(defn schedule-assocs-with-cron-expr-for-node
  "Retrieves all schedule associations for the given node id"
  [c node-id]
  (->> (schedule-assocs-for-node c node-id)
       (assoc-cron-expr c)))

(defn schedule-assoc-ids-for-node
  "Retrieves all schedule association ids for the given node id"
  [c node-id]
  (->> (schedule-assocs-for-node c node-id) (map :node-schedule-id)))

(defn schedule-assocs-for-schedule
  "Retrieves all schedule associations for the given schedule id"
  [c schedule-id]
  (filter-schedule-assocs c #(= schedule-id (:schedule-id %1))))

(defn schedules-for-node
  "Retrieves all schedule for the given node id"
  [c node-id]
  (let [schedule-ids (->> (schedule-assocs-for-node c node-id) (map :schedule-id))]
    (map (partial schedule c) schedule-ids)))

(defn node
  "Answers with either the job or workflow for the given node-id"
  [c node-id]
  (or (job c node-id) (workflow c node-id)))

(defn put-node
  "Saves the node to either the jobs or workflows based on the node type id."
  [c {:keys [node-type-id] :as n}]
  (if (util/workflow-type? node-type-id)
    (put-workflow c n)
    (put-job c n)))

(defn nodes-for-schedule
  "Retrieves all nodes for the given schedule id"
  [c schedule-id]
  (let [node-ids (->> (schedule-assocs-for-schedule c schedule-id) (map :node-id))]
    (map (partial node c) node-ids)))

(defn replace-schedule-assocs
  "Removes the old schedule associations if any and adds in the new ones."
  [c node-id new-assocs]
  (let [old-ids (schedule-assoc-ids-for-node c node-id)]
    (-> c
        (rm-schedule-assocs old-ids)
        (put-schedule-assocs new-assocs))))




(defn agent-name-for-job-id
  "Answers with the agent name for the job-id."
  [c job-id]
  (let [{:keys [agent-id]} (job c job-id)]
    (assert agent-id (str "No agent-id for job-id " job-id))
    (-> c (agent agent-id) :agent-name)))


(defn alerts-for-status
  "Answers with a seq of alert instances filtered by alert-ids and success?"
  [c alert-ids success?]
  (filter (fn [{:keys [alert-id is-for-error]}]
            (and (contains? alert-ids alert-id)
                 (not= success? is-for-error)))
          (alerts c)))
