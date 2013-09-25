# Job Scheduling Kit
Quartz backed job scheduling.
Provides UI.

## Usage

```shell

mkdir log
touch log/jsk.log
tail -f log/jsk.log

```

* In LightTable connect to the project by CMD+Enter in jsk/repl.clj
* Open an instarepl

```clojure

(load "jsk/repl")       ; loads jsk.repl namespace which exposes start-server and stop-server fns
(jsk.repl/start-server) ; starts the server
(jsk.repl/stop-server)  ; stops the server

```


## Enfocus notes
Can't have anything other than actual selectors and functions in defaction,
defsnippet and deftemplate etc.

Have to pass strings to ef/content. doesn't like ints even.


## TODO
Quartz doesn't have the concept of a schedule which should exist.
Should create a job and associate schedules to that job.  Schedule is a
record of id, name, description, and a cron expression

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
