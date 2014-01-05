(ns jsk.tracker)


;; for tracking of jobs agents and status of each



(defn new-tracker
  "Create a new tracker."
  []
  {:agents {}
   :jobs {}})


(defprotocol ITracker
  "Tracking of jobs, agents and status."

  (add-agent [t agent-id ts]
    "Adds the agent specified by agent-id to the tracker t.")

  (rm-agent [t agent-id]
    "Removes the agent specified by agent-id from the tracker t.")

  (rm-agents [t agent-ids]
    "Removes the agents specified by agent-ids from the tracker t.")

  (agent-heartbeat-rcvd [t agent-id ts]
    "Agent heartbeat received.")

  (run-job [t agent-id job-id ts]
    "Mark that the agent was sent the run-job req")

  (agent-started-job [t agent-id job-id ts]
    "Mark the time the agent actually started the job.")

  (abort-job [t agent-id job-id ts]
    "Mark that the agent was sent the abort request.")

  (rm-job [t agent-id job-id]
    "Removes the job from this tracker.")

  (agents [t]
    "Answers with all the agent ids.")

  (agent-exists? [t agent-id]
    "Answers if the agent exists.")

  (last-heartbeat [t agent-id]
    "Last received heartbeat timestamp (milliseconds) for the agent.")

  (dead-agents [t ts]
    "Answers with a seq of agent-ids whose last heartbeat is older than ts.
     ts is the time in milliseconds.")

  (dead-agents-job-map [t ts]
    "Answers with a map of agent-ids to a set of their jobs")

  (job-exists? [t job-id]
    "Answers if the job exists.")

  (agent-jobs [t agent-id]
    "Answers with a set of the agents jobs"))


(extend-protocol ITracker

  clojure.lang.IPersistentMap

  (add-agent [t agent-id ts]
    (assoc-in t [:agents agent-id] {:last-hb ts
                                    :jobs #{}}))

  (rm-agent [t agent-id]
    (update-in t [:agents] dissoc agent-id))

  (rm-agents [t agent-ids]
    (reduce (fn[trkr id] (rm-agent trkr id))
            t
            agent-ids))

  (agent-heartbeat-rcvd [t agent-id ts]
    (assoc-in t [:agents agent-id :last-hb] ts))

  (run-job [t agent-id job-id ts]
    (-> t
        (update-in [:agents agent-id :jobs] conj job-id)
        (assoc-in [:jobs job-id] {:state :run-job :ts ts})))

  (agent-started-job [t agent-id job-id ts]
    (assoc-in t [:jobs job-id] {:state :run-job-ack :ts ts}))

  (abort-job [t agent-id job-id ts]
    (assoc-in [:jobs job-id] {:state :abort-job :ts ts}))

  (rm-job [t agent-id job-id]
    (-> t
        (update-in [:agents agent-id :jobs] disj job-id)
        (update-in [:jobs] dissoc job-id)))

  (agents [t]
    (-> t :agents keys))

  (agent-exists? [t agent-id]
    (-> t (get-in [:agents agent-id]) nil? not))

  (last-heartbeat [t agent-id]
    (get-in t [:agents agent-id :last-hb]))

  (dead-agents [t ts]
    (filter #(< (last-heartbeat t %1) ts) (agents t)))

  (dead-agents-job-map [t ts]
    (reduce (fn[m id] (assoc m id (agent-jobs t id)))
            {}
            (dead-agents t ts)))

  (job-exists? [t job-id]
    (-> t (get-in [:jobs job-id]) nil? not))

  (agent-jobs [t agent-id]
    (get-in t [:agents agent-id :jobs])))


