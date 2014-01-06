(ns jsk.cache-test
  (:use midje.sweet)
  (:require [jsk.cache :as cache]))

(def node-id 42)
(def schedule-id 23)
(def node-schedule-id 31)


(def test-node {:node-id node-id :node-nm "NodeName"})

(def test-schedule {:schedule-id schedule-id :sched-nm "SchedName"})

(def test-schedule-assoc {:node-schedule-id node-schedule-id
                          :node-id node-id
                          :schedule-id schedule-id})

;-----------------------------------------------------------------------
; Nodes
;-----------------------------------------------------------------------
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

;-----------------------------------------------------------------------
; Schedules
;-----------------------------------------------------------------------
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

;-----------------------------------------------------------------------
; Associations
;-----------------------------------------------------------------------
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










