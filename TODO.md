Top Priority
-------------
* Agents



## TODO
* Variables
* Timeout feature
* Handle Vertex with 2 or more inbound edges. For simplicity, assume all have to have executed successfully
  What about a workflow with 3 starting nodes that must finish before going on?
  ie job 1, 2, 3 can be run in parallel, when all 3 finish do job 4.
  SystemNode? It's like a Countdown lock.
* Number of retries when a job fails
* Allow multiple instances of a job to run concurrently or not.
* Node search a la suggest in wf designer instead of long list on the left.
* Replace enfocus with om
* Looping
* View logs in UI
* File/Directory monitor jobs


## Bugs
Resuming a workflow/job doesn't set execution status to executing in DB.



## SQL Korma notes
; generates slow sql via nested inline views
(def wf-execution-search-base
  (-> (select* execution)
      (fields :execution-id
              :status-id
              :start-ts
              :finish-ts
              [:node.node-name :execution-name])
      (join :inner :execution-workflow (= :execution-id :execution-workflow.execution-id))
      (join :inner :node (= :execution-workflow.workflow-id :node.node-id))
      (where (and (= :execution-workflow.root true)
                  (= :node.is-system false)))))

(def job-execution-search-base
  (-> (select* execution)
      (fields :execution-id
              :status-id
              :start-ts
              :finish-ts
              [:jn.node-name :execution-name])
      (join :inner :execution-workflow (= :execution-id :execution-workflow.execution-id))
      (join :inner [:node :wfn] (= :execution-workflow.workflow-id :wfn.node-id))
      (join :inner :execution-vertex (= :execution-workflow.workflow-id :execution-vertex.execution-workflow-id))
      (join :inner [:node :jn] (= :execution-vertex.node-id :jn.node-id))
      (where (and (= :execution-workflow.root true)
                  (= :wfn.is-system true)))))

(sql-only (-> wf-execution-search-base select))
(sql-only (-> job-execution-search-base select))


## JS notes
jquery slide left code: http://jsfiddle.net/adeneo/VN8es/1/
for the job list panel
