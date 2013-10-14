# Job Scheduling Kit
Quartz backed job scheduling.
Provides UI.

## Usage

```shell

mkdir log
touch log/jsk.log
tail -f log/jsk.log

```

## Quartz
There are a few table:
* job
* schedule
* job_schedule

Schedules are identified by the schedule_id from the schedule table.

Jobs within Quartz are identified by the job_id from the job table.
Quartz's concept of a trigger is really the concept of a job and a schedule within JSK.
Triggers are identified by the job_schedule_id from the job_schedule table.


Saving a job always replaces any existing instance registered with Quartz.

Right now schedule associations are a bit stupid.
It first deletes all associations and then adds whatever might be selected.


## Enfocus notes
Can't have anything other than actual selectors and functions in defaction,
defsnippet and deftemplate etc.

Have to pass strings to ef/content. doesn't like ints even.


## TODO

* On startup read jobs and schedules from database and create jobs and triggers
* UI for displaying job executions
  * Have multiple sections
    a. Executing
    b. Finished Successfully
    c. Errored



Three types are most important:
* Cron/Calendar
* Simple ie do x every 5 minutes
   (also specify when active/not between 5 am and 10pm)
* Event based ie file/directory watch


** Cron
- UI will translate interface to cron expression




## License

Copyright © 2013 Amar Mehta

Distributed under the Eclipse Public License, the same as Clojure.
