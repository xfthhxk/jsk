Agent protocol
--------------

agent starts connects to coordinator with identity

agent subscribes to agent messages topic.

coordinator keeps track of jobs to agents, publishes with that identity when picking an agent using a round Robin pattern for picking among agents.

agent filters on its identity. picks up a job request msg and acts accordingly.
job-start, job-abort
using push socket, tells coordinator it has started/aborted job.
when job finished send msg to coordinator with id, status, etc.

the coordinator does a ping request to all agents, a broadcast msg. agents respond. no response after 3 pings, remove agent from available set. send email alert to humans.

or

should agents check in periodically on their own with their status of jobs etc. how does agent know coordinator is alive? agent should send email to human if no contact with server in last N secs?

how does the web server know about what's happening? agents should publish to a topic?

web server can talk to coordinator to run adhoc jobs. schedule, job and schedule association changes need to be communicated to coordinator so it can reload it's data, update quartz runtime.

on coordinator start, publish msg to agents to register themselves.
then do broadcast pings every few secs


Top Priority
-------------
Better UI
* create directories, add jobs, workflows
* push notification data whenever directories/jobs/workflows are added, removed, updated
* ui needs to listen for explorer notifications and add, remove or update item

* ui gets data pushed from server. 
* ui checks to see if the parent directory is visible in the tree.
  if not do nothing, as if that directory eventually is visible, data
  will be loaded synchronously
  if in tree, then apply the change, ie update name and parent dir as
  required, add to tree


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
* Search for jobs/wfs by schedule
* We're snapshotting the dependencies but not the actual job definition.


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
