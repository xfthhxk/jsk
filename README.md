# Job Scheduling Kit
Quartz backed job scheduling.
Provides UI.

## Usage

```shell

mkdir log
touch log/jsk.log
tail -f log/jsk.log

```


## Enfocus notes
Can't have anything other than actual selectors and functions in defaction,
defsnippet and deftemplate etc.

Have to pass strings to ef/content. doesn't like ints even.


## TODO
Updating a schedule should find all triggers associated with the schedule and
update them by calling Scheduler.rescheduleJob(Trigger old, Trigger new)
  * Find all job-schedule-id instances where the schedule-id is x.
  * Create new triggers for them and call rescheduleJob

On startup read jobs and schedules from database and create jobs and triggers


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
