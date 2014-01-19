(ns jsk.conductor.cache-test
  (:use midje.sweet)
  (:require [jsk.conductor.cache :as cache]))

(def job-id 42)
(def node-id job-id)
(def schedule-id 23)
(def node-schedule-id 31)

(def wf-id 123)

(def test-job {:job-id job-id :job-nm "Job Name"})
(def test-wf {:workflow-id wf-id :workflow-nm "Workflow Name"})

(def test-schedule {:schedule-id schedule-id :sched-nm "SchedName"})

(def test-schedule-assoc {:node-schedule-id node-schedule-id
                          :node-id job-id
                          :schedule-id schedule-id})

(def test-jobs #{test-job {:job-id 91 :jobs-nm "SomeOtherJob"}})
(def test-wfs #{test-wf {:workflow-id 234 :workflow-nm "Other Workflow Name"}})


(def test-schedules #{test-schedule {:schedule-id 231 :sched-nm "SomeOtherSchedule"}})

(def test-schedule-assocs #{test-schedule-assoc
                            {:node-schedule-id 829
                             :node-id 91
                             :schedule-id 99}})

;-----------------------------------------------------------------------
; Jobs
;-----------------------------------------------------------------------
(facts "Jobs"
  (fact "Put job."
    (-> (cache/new-cache)
        (cache/put-job test-job)) => truthy)


  (fact "Retrieve job."
    (-> (cache/new-cache)
        (cache/put-job test-job)
        (cache/job job-id)) => test-job)

  (fact "Remove job."
    (let [c (-> (cache/new-cache)
                (cache/put-job test-job))
          c' (cache/rm-job c job-id)]

      (cache/job c  job-id) => test-job
      (cache/job c' job-id) => nil))

  (fact "Put multiple jobs."
    (-> (cache/new-cache)
        (cache/put-jobs test-jobs)) => truthy)

  (fact "Retrieve all jobs."
    (let [c (-> (cache/new-cache)
                (cache/put-jobs test-jobs))]
      (set (cache/jobs c)) => test-jobs)))

;-----------------------------------------------------------------------
; Workflows
;-----------------------------------------------------------------------
(facts "Workflows"
  (fact "Put workflow."
    (-> (cache/new-cache)
        (cache/put-workflow test-wf)) => truthy)


  (fact "Retrieve workflow."
    (-> (cache/new-cache)
        (cache/put-workflow test-wf)
        (cache/workflow wf-id)) => test-wf)

  (fact "Remove workflow."
    (let [c (-> (cache/new-cache)
                (cache/put-workflow test-wf))
          c' (cache/rm-workflow c wf-id)]

      (cache/workflow c  wf-id) => test-wf
      (cache/workflow c' wf-id) => nil))

  (fact "Put multiple workflows."
    (-> (cache/new-cache)
        (cache/put-workflows test-wfs)) => truthy)

  (fact "Retrieve all workflows."
    (let [c (-> (cache/new-cache)
                (cache/put-workflows test-wfs))]
      (set (cache/workflows c)) => test-wfs)))

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
       (cache/put-job test-job)
       (cache/put-schedule test-schedule)
       (cache/put-assoc test-schedule-assoc)
       (cache/schedules-for-node (inc node-id))) => empty?)

 (fact "Finds schedules when in cache."
   (let [c (-> (cache/new-cache)
               (cache/put-job test-job)
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
       (cache/put-job test-job)
       (cache/put-schedule test-schedule)
       (cache/put-assoc test-schedule-assoc)
       (cache/nodes-for-schedule (inc schedule-id))) => empty?)

 (fact "Finds schedules when in cache."
   (let [c (-> (cache/new-cache)
               (cache/put-job test-job)
               (cache/put-schedule test-schedule)
               (cache/put-assoc test-schedule-assoc))
         r  (cache/nodes-for-schedule c schedule-id)]
     (count r) => 1
     (first r) => test-job)))

(facts "Generic node"
  (let [c (-> (cache/new-cache)
              (cache/put-job test-job)
              (cache/put-workflow test-wf))]

    (fact "Lookup job"
      (cache/node c job-id) => test-job)

    (fact "Lookup wf"
      (cache/node c wf-id) => test-wf)))










