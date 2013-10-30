Top Priority
-------------
jquery slide left code: http://jsfiddle.net/adeneo/VN8es/1/
for the job list panel

Make designer area a jquery droppable

Handle on drop event
  * move the dragged item back to where it was on the  source panel.
  * add a new workflow-node item to the designer
  * associate with jsplumb




## TODO
* Job Workflow
  workflow
    workflow_id
    job_id
    job_id_exit_status_id (success/fail for job_id)
    next_workflow_id

    Should be able to use sql's with recursive to get the whole tree?

  workflow_execution
    workflow_execution_id
    workflow_id
    job_id
    job_id_exit_status_id
    next_job_id
    has_executed




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
