(ns jsk.conductor.cache-test
  (:use midje.sweet)
  (:require [jsk.conductor.cache :as cache]))

(def node-id 42)
(def schedule-id 23)
(def node-schedule-id 31)


(def test-node {:node-id node-id :node-nm "NodeName"})

(def test-schedule {:schedule-id schedule-id :sched-nm "SchedName"})

(def test-schedule-assoc {:node-schedule-id node-schedule-id
                          :node-id node-id
                          :schedule-id schedule-id})

(def test-nodes #{test-node {:node-id 91 :node-nm "SomeOtherNode"}})
(def test-schedules #{test-schedule {:schedule-id 231 :node-nm "SomeOtherSchedule"}})

(def test-schedule-assocs #{test-schedule-assoc
                            {:node-schedule-id 829
                             :node-id 91
                             :schedule-id 99}})

;-----------------------------------------------------------------------
; Nodes
;-----------------------------------------------------------------------
(facts "Nodes"
  (fact "Put node."
    (-> (cache/new-cache)
        (cache/put-node test-node)) => truthy)


  (fact "Retrieve node."
    (-> (cache/new-cache)
        (cache/put-node test-node)
        (cache/node node-id)) => test-node)

  (fact "Remove node."
    (let [c (-> (cache/new-cache)
                (cache/put-node test-node))
          c' (cache/rm-node c node-id)]

      (cache/node c  node-id) => test-node
      (cache/node c' node-id) => nil))

  (fact "Put multiple nodes."
    (-> (cache/new-cache)
        (cache/put-nodes test-nodes)) => truthy)

  (fact "Retrieve all nodes."
    (let [c (-> (cache/new-cache)
                (cache/put-nodes test-nodes))]
      (set (cache/nodes c)) => test-nodes)))

;-----------------------------------------------------------------------
; Schedules
;-----------------------------------------------------------------------
(facts "Schedules"
  (fact "Put schedule."
    (-> (cache/new-cache)
        (cache/put-schedule test-schedule)) => truthy)

  (fact "Retrieve schedule."
    (-> (cache/new-cache)
        (cache/put-schedule test-schedule)
        (cache/schedule schedule-id)) => test-schedule)

  (fact "Remove schedule."
    (let [c (-> (cache/new-cache)
                (cache/put-schedule test-schedule))
          c' (cache/rm-schedule c schedule-id)]

      (cache/schedule c  schedule-id) => test-schedule
      (cache/schedule c' schedule-id) => nil))

  (fact "Put multiple schedules."
    (-> (cache/new-cache)
        (cache/put-schedules test-schedules)) => truthy)

  (fact "Retrieve all schedules."
    (let [c (-> (cache/new-cache)
                (cache/put-schedules test-schedules))]
      (set (cache/schedules c)) => test-schedules)))


;-----------------------------------------------------------------------
; Associations
;-----------------------------------------------------------------------
(facts "Node Schedule Associations"
  (fact "Put assoc."
    (-> (cache/new-cache)
        (cache/put-assoc test-schedule-assoc)) => truthy)

  (fact "Retrieve assoc."
    (-> (cache/new-cache)
        (cache/put-assoc test-schedule-assoc)
        (cache/schedule-assoc node-schedule-id)) => test-schedule-assoc)

  (fact "Remove assoc."
    (let [c (-> (cache/new-cache)
                (cache/put-assoc test-schedule-assoc))
          c' (cache/rm-assoc c node-schedule-id)]

      (cache/schedule-assoc c  node-schedule-id) => test-schedule-assoc
      (cache/schedule-assoc c' node-schedule-id) => nil))

  (fact "Put multiple schedule associations."
    (-> (cache/new-cache)
        (cache/put-assoc test-schedule-assoc)) => truthy)

  (fact "Retrieve all schedules associations."
    (let [c (-> (cache/new-cache)
                (cache/put-assocs test-schedule-assocs))]
      (set (cache/schedule-assocs c)) => test-schedule-assocs)))



;-----------------------------------------------------------------------
; Cache Querying
;-----------------------------------------------------------------------
(facts "Schedules associations for node-id"
 (fact "Empty coll when cache empty."
   (-> (cache/new-cache)
       (cache/schedule-assocs-for-node node-id)) => empty?)

 (fact "Empty coll when assoc not in cache."
   (-> (cache/new-cache)
       (cache/put-assoc test-schedule-assoc)
       (cache/schedule-assocs-for-node (inc node-id))) => empty?)

 (fact "Finds assoc when in cache."
   (let [c (-> (cache/new-cache)
               (cache/put-assoc test-schedule-assoc))
         r  (cache/schedule-assocs-for-node c node-id)]
     (count r) => 1
     (first r) => test-schedule-assoc)))


(facts "Schedules associations for schedule-id"
 (fact "Empty coll when cache empty."
   (-> (cache/new-cache)
       (cache/schedule-assocs-for-schedule schedule-id)) => empty?)

 (fact "Empty coll when assoc not in cache."
   (-> (cache/new-cache)
       (cache/put-assoc test-schedule-assoc)
       (cache/schedule-assocs-for-schedule (inc schedule-id))) => empty?)

 (fact "Finds assoc when in cache."
   (let [c (-> (cache/new-cache)
               (cache/put-assoc test-schedule-assoc))
         r  (cache/schedule-assocs-for-schedule c schedule-id)]
     (count r) => 1
     (first r) => test-schedule-assoc)))


(facts "Schedules for node-id"
 (fact "Empty coll when cache empty."
   (-> (cache/new-cache)
       (cache/schedules-for-node node-id)) => empty?)

 (fact "Empty coll when assoc not in cache."
   (-> (cache/new-cache)
       (cache/put-node test-node)
       (cache/put-schedule test-schedule)
       (cache/put-assoc test-schedule-assoc)
       (cache/schedules-for-node (inc node-id))) => empty?)

 (fact "Finds schedules when in cache."
   (let [c (-> (cache/new-cache)
               (cache/put-node test-node)
               (cache/put-schedule test-schedule)
               (cache/put-assoc test-schedule-assoc))
         r  (cache/schedules-for-node c node-id)]
     (count r) => 1
     (first r) => test-schedule)))

(facts "Nodes for schedule-id"
 (fact "Empty coll when cache empty."
   (-> (cache/new-cache)
       (cache/nodes-for-schedule schedule-id)) => empty?)

 (fact "Empty coll when assoc not in cache."
   (-> (cache/new-cache)
       (cache/put-node test-node)
       (cache/put-schedule test-schedule)
       (cache/put-assoc test-schedule-assoc)
       (cache/nodes-for-schedule (inc schedule-id))) => empty?)

 (fact "Finds schedules when in cache."
   (let [c (-> (cache/new-cache)
               (cache/put-node test-node)
               (cache/put-schedule test-schedule)
               (cache/put-assoc test-schedule-assoc))
         r  (cache/nodes-for-schedule c schedule-id)]
     (count r) => 1
     (first r) => test-node)))










