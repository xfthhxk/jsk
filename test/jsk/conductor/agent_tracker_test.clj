(ns jsk.conductor.agent-tracker-test
  (:use midje.sweet)
  (:require [jsk.conductor.agent-tracker :as at]
            [jsk.common.util :as util]))


(def agent-1 "agent-1")
(def agent-2 "agent-2")

(fact "Add agent"
  (-> (at/new-tracker)
      (at/add-agent agent-1 (util/now-ms))) => truthy)

(facts "Agent existance"
  (fact "Agent exists"
    (-> (at/new-tracker)
        (at/add-agent agent-1 (util/now-ms))
        (at/agent-exists? agent-1)) => true)

  (fact "Agent exists negative"
    (-> (at/new-tracker)
        (at/add-agent agent-1 (util/now-ms))
        (at/agent-exists? agent-2)) => false))

(fact "Agent retrieveal"
  (-> (at/new-tracker)
      (at/add-agent agent-1 (util/now-ms))
      (at/add-agent agent-2 (util/now-ms))
      (at/agents)
      set) => #{agent-1 agent-2})

(fact "Rm agent"
  (-> (at/new-tracker)
      (at/add-agent agent-1 (util/now-ms))) => truthy)

(fact "Rm agents"
  (-> (at/new-tracker)
      (at/add-agent agent-1 (util/now-ms))
      (at/add-agent agent-2 (util/now-ms))
      (at/rm-agents [agent-1 agent-2])) => {:agents {}})




(fact "Agent job assoc"
  (-> (at/new-tracker)
      (at/add-agent agent-1 (util/now-ms))
      (at/add-agent-job-assoc agent-1 123 456)) => truthy)

(fact "Retrieve agent jobs"
  (-> (at/new-tracker)
      (at/add-agent agent-1 (util/now-ms))
      (at/add-agent-job-assoc agent-1 123 456)
      (at/agent-jobs agent-1)) => #{123 456})

(facts "Job agent assocs via map"
  (defn populate []
    (-> (at/new-tracker)
        (at/add-agent agent-1 (util/now-ms))
        (at/add-agent agent-2 (util/now-ms))
        (at/add-job-agent-assocs {123 agent-1 456 agent-2})))

  (fact "populate via map"
    (populate) => truthy)

  (fact "retrieve populate via map"
    (-> (populate)
        (at/agent-jobs agent-1)) => #{123}))

(fact "Retrieve job agent assocs"
  (-> (at/new-tracker)
      (at/add-agent agent-1 (util/now-ms))
      (at/add-agent agent-2 (util/now-ms))
      (at/add-job-agent-assocs {123 agent-1 456 agent-2})) => truthy)



(fact "Remove agent job assoc"
  (-> (at/new-tracker)
      (at/add-agent agent-1 (util/now-ms))
      (at/add-agent-job-assoc agent-1 123 456)
      (at/rm-agent-job-assoc agent-1 456)
      (at/agent-jobs agent-1)) => #{123})

;-----------------------------------------------------------------------
; Heartbeats
;-----------------------------------------------------------------------
(facts "Heartbeats"
  (fact "Agent heartbeat received"
    (-> (at/new-tracker)
        (at/add-agent agent-1 (util/now-ms))
        (at/agent-heartbeat-rcvd agent-1 1234)) => truthy)

  (fact "Retrieve last heartbeat for agent"
    (-> (at/new-tracker)
        (at/add-agent agent-1 (util/now-ms))
        (at/agent-heartbeat-rcvd agent-1 1234)
        (at/last-heartbeat agent-1)) => 1234)

  (fact "Find dead agents"
    (-> (at/new-tracker)
        (at/add-agent agent-1 500)
        (at/add-agent agent-2 1000)
        (at/dead-agents 501)) => [agent-1])

  (fact "Dead agent job map"
    (-> (at/new-tracker)
        (at/add-agent agent-1 500)
        (at/add-agent agent-2 (util/now-ms))
        (at/add-job-agent-assocs {123 agent-1 456 agent-2})
        (at/dead-agents-job-map 501)) => {agent-1 #{123}}))


