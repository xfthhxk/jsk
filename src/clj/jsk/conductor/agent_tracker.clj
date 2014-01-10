(ns jsk.conductor.agent-tracker)

;  "Tracking of jobs, agents and status."

(defn new-tracker
  "Create a new tracker."
  []
  {:agents {}
   :jobs {}})

(defn add-agent
  "Adds the agent specified by agent-id to the tracker t."
  [t agent-id ts]
  (assoc-in t [:agents agent-id] {:last-hb ts
                                  :jobs #{}}))

(defn rm-agent
  "Removes the agent specified by agent-id from the tracker t."
  [t agent-id]
  (update-in t [:agents] dissoc agent-id))

(defn rm-agents
  "Removes the agents specified by agent-ids from the tracker t."
  [t agent-ids]
  (reduce (fn[trkr id] (rm-agent trkr id))
          t
          agent-ids))

(defn agent-heartbeat-rcvd
  "Agent heartbeat received."
  [t agent-id ts]
  (assoc-in t [:agents agent-id :last-hb] ts))

(defn run-job
  "Mark that the agent was sent the run-job req"
  [t agent-id job-id ts]
  (-> t
      (update-in [:agents agent-id :jobs] conj job-id)
      (assoc-in [:jobs job-id] {:state :run-job :ts ts})))

(defn agent-started-job
  "Mark the time the agent actually started the job."
  [t agent-id job-id ts]
  (assoc-in t [:jobs job-id] {:state :run-job-ack :ts ts}))

(defn abort-job
  "Mark that the agent was sent the abort request."
  [t agent-id job-id ts]
  (assoc-in [:jobs job-id] {:state :abort-job :ts ts}))

(defn rm-job
  "Removes the job from this tracker."
  [t agent-id job-id]
  (-> t
      (update-in [:agents agent-id :jobs] disj job-id)
      (update-in [:jobs] dissoc job-id)))

(defn agents
  "Answers with all the agent ids."
  [t]
  (-> t :agents keys))

(defn agent-exists?
  "Answers if the agent exists."
  [t agent-id]
  (-> t (get-in [:agents agent-id]) nil? not))

(defn last-heartbeat
  "Last received heartbeat timestamp (milliseconds) for the agent."
  [t agent-id]
  (get-in t [:agents agent-id :last-hb]))


(defn dead-agents
  "Answers with a seq of agent-ids whose last heartbeat is older than ts.
   ts is the time in milliseconds."
  [t ts]
  (filter #(< (last-heartbeat t %1) ts) (agents t)))

(defn agent-jobs
  "Answers with a set of the agents jobs"
  [t agent-id]
  (get-in t [:agents agent-id :jobs]))

(defn dead-agents-job-map
  "Answers with a map of agent-ids to a set of their jobs"
  [t ts]
  (reduce (fn[m id] (assoc m id (agent-jobs t id)))
          {}
          (dead-agents t ts)))

(defn job-exists?
  "Answers if the job exists."
  [t job-id]
  (-> t (get-in [:jobs job-id]) nil? not))

