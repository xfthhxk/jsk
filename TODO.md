Top Priority
-------------
* Validations, make sure it is an acyclic digraph
* Implement actual logic on server to do the deps at job runtime.


* What about a workflow with 3 starting nodes that must finish before going on?
  ie job 1, 2, 3 can be run in parallel, when all 3 finish do job 4.
  SystemNode? It's like a Countdown lock.


jquery slide left code: http://jsfiddle.net/adeneo/VN8es/1/
for the job list panel




## TODO
* Sometimes the execution-workflow's finish-ts is not set thought it completes
  Seems to occur with nested workflows

* Execution-vertex for workflow nodes doesn't record the start/finish ts
  see db/workflow-finished and db/workflow-started

* in the execution info object, need a mapping of exec-wf-id to the execution-vertex-id
  that workflow corresponds to.  This would facilitate with info notifications to the
  ui etc.



* Number of retries when a job fails
* Job variables
* Allow multiple instances of a job to run concurrently or not.
* Job timeout.

## DONE
* Basic Job failure emails
* Configure job execution output directory.
* On startup read jobs and schedules from database and create jobs and triggers
* Run now
* UI for displaying job executions
  * Have multiple sections
    a. Executing
    b. Finished Successfully
    c. Errored

* Save layout of workflow nodes to reconstruct for display.
* Save graph to server
