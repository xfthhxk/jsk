(ns jsk.cache
  "Cache of jobs and schedules")

(defn new-cache [] {:nodes {}
                    :schedules {}
                    :node-schedules {} })

(defprotocol ICache
  "Cache of nodes and schedules and their associations."

  (put-node [c n]
    "Puts the node n in cache c.")

  (node [c id]
    "Gets the node for the id or nil")

  (rm-node [c id]
    "Removes the node with id from the cache c.")

  (put-schedule [c s]
    "Puts the schedule s in cache c.")

  (schedule [c id]
    "Gets the schedule for the id or nil")

  (rm-schedule [c id]
    "Removes the schedule with id from the cache c.")

  (put-assoc [c node-schedule]
    "Puts the association in the cache.")

  (schedule-assoc [c id]
    "Gets the schedule-assoc for the id or nil")

  (rm-assoc [c node-schedule-id]
    "Removes the schedule association")

  (schedule-assocs-for-node [c node-id]
    "Gets the schedule-assocs for the node-id")

  (schedule-assocs-for-schedule [c schedule-id]
    "Gets the schedule-assocs for the schedule-id")

  (schedules-for-node [c node-id]
    "Gets the schedules for the node-id")

  (nodes-for-schedule [c schedule-id]
    "Gets the schedules for the node-id"))


(extend-protocol ICache

  clojure.lang.IPersistentMap

  (put-node [c {:keys [node-id] :as node}]
    (assoc-in c [:nodes node-id] node))

  (node [c node-id]
    (get-in c [:nodes node-id]))

  (rm-node [c node-id]
    (update-in c [:nodes] dissoc node-id))

  (put-schedule [c {:keys [schedule-id] :as schedule}]
    (assoc-in c [:schedules schedule-id] schedule))

  (schedule [c schedule-id]
    (get-in c [:schedules schedule-id]))

  (rm-schedule [c schedule-id]
    (update-in c [:schedules] dissoc schedule-id))

  (put-assoc [c {:keys [node-schedule-id] :as node-sched}]
    (assoc-in c [:node-schedules node-schedule-id] node-sched))

  (schedule-assoc [c node-schedule-id]
    (get-in c [:node-schedules node-schedule-id]))

  (rm-assoc [c node-schedule-id]
    (update-in c [:node-schedules] dissoc node-schedule-id))

  (schedule-assocs-for-node [c node-id]
    (let [matches-id? #(= node-id (:node-id %1))]
      (->> c :node-schedules vals (filter matches-id?))))

  (schedule-assocs-for-schedule [c schedule-id]
    (let [matches-id? #(= schedule-id (:schedule-id %1))]
      (->> c :node-schedules vals (filter matches-id?))))

  (schedules-for-node [c node-id]
    (let [schedule-ids (->> (schedule-assocs-for-node c node-id) (map :schedule-id))]
      (map (partial schedule c) schedule-ids)))

  (nodes-for-schedule [c schedule-id]
    (let [node-ids (->> (schedule-assocs-for-schedule c schedule-id) (map :node-id))]
      (map (partial node c) node-ids))))






