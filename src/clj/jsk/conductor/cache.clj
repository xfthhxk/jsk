(ns jsk.conductor.cache
  "Cache of jobs and schedules"
  (:refer-clojure :exclude [agent]))

(defn new-cache [] {:agents {}
                    :nodes {}
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
; Nodes
;-----------------------------------------------------------------------
(defn put-node
  "Puts node in cache c."
  [c {:keys [node-id] :as node}]
    (assoc-in c [:nodes node-id] node))

(defn put-nodes
  "Puts nodes in the collection nn in cache c."
  [c nn]
  (reduce #(put-node %1 %2) c nn))

(defn node
  "Gets the node for the id. Can return nil if node-id is unknown."
  [c node-id]
  (get-in c [:nodes node-id]))

(defn nodes
  "Gets all nodes in cache c."
  [c]
  (-> c :nodes vals))

(defn rm-node
  "Removes the node specified by the node-id."
  [c node-id]
  (update-in c [:nodes] dissoc node-id))

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
(defn put-assoc
  "Puts the node-sched association in to cache c."
  [c {:keys [node-schedule-id] :as node-sched}]
  (assoc-in c [:node-schedules node-schedule-id] node-sched))

(defn put-assocs
  "Puts all associations in node-schedule-associations in to cache c."
  [c node-schedule-associations]
  (reduce #(put-assoc %1 %2) c node-schedule-associations))

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

(defn schedule-assocs-with-cron-expr
  "Retrieves all node schedule association with the associated :cron-expression. "
  [c]
  (->> c schedule-assocs (assoc-cron-expr c)))

(defn rm-assoc
  "Removes the association pointed to by node-schedule-id."
  [c node-schedule-id]
  (update-in c [:node-schedules] dissoc node-schedule-id))

(defn rm-assocs
  "Removes all associations pointed to by node-schedule-ids."
  [c node-schedule-ids]
  (reduce #(rm-assoc %1 %2) c node-schedule-ids))


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
  (->> c (schedule-assocs-for-node c node-id) (map :node-schedule-id)))

(defn schedule-assocs-for-schedule
  "Retrieves all schedule associations for the given schedule id"
  [c schedule-id]
  (filter-schedule-assocs c #(= schedule-id (:schedule-id %1))))

(defn schedules-for-node
  "Retrieves all schedule for the given node id"
  [c node-id]
  (let [schedule-ids (->> (schedule-assocs-for-node c node-id) (map :schedule-id))]
    (map (partial schedule c) schedule-ids)))

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
        (rm-assocs old-ids)
        (put-assocs new-assocs))))




