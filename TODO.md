Top Priority
-------------
* Breadcrumb for drilling down into wfs.
  - UI right now replaces everything via util/showcase
* Visual status updates in visualizer.
* Make workflow designer / exec visualizer look pretty




jquery slide left code: http://jsfiddle.net/adeneo/VN8es/1/
for the job list panel




## TODO
* Variables
* Abort node and all subnodes recursively
* Timeout feature
* Handle Vertex with 2 or more inbound edges. For simplicity, assume all have to have executed successfully
  What about a workflow with 3 starting nodes that must finish before going on?
  ie job 1, 2, 3 can be run in parallel, when all 3 finish do job 4.
  SystemNode? It's like a Countdown lock.
* Number of retries when a job fails
* Allow multiple instances of a job to run concurrently or not.
* Agent support
* Node search a la suggest in wf designer instead of long list on the left.


## Other
* Looping
* After agents, be able to push code to remote machines and then run it.
* View logs in UI


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
* Sometimes the execution-workflow's finish-ts is not set thought it completes
  Seems to occur with nested workflows

* Execution-vertex for workflow nodes doesn't record the start/finish ts
  see db/workflow-finished and db/workflow-started

* in the execution info object, need a mapping of exec-wf-id to the execution-vertex-id
  that workflow corresponds to.  This would facilitate with info notifications to the
  ui etc.

* Save layout of workflow nodes to reconstruct for display.
* Save graph to server
