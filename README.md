# Job Scheduling Kit
What is it?

* Quartz backed job scheduling with dependency management.
* Provides a UI
    - creating schedules, jobs, workflows
    - viewing executions
    - aborting and resuming executions
    - search for past executions
    - ability to manage job dependencies visually
    - execution lifecycle events are sent to the dashboard without need for refreshing

Terminology:
  - job: Something to execute
  - workflow: A directed graph of jobs which can be composed with other workflows.

## Usage

* Start h2 db
* Run the sql script located at sql/schema.sql
    - make changes to app_user to put in your email id
* Update resources/conf/jsk-conf.clj as needed

```shell
mkdir log
touch log/jsk.log
tail -f log/jsk.log
lein run
```

Connect to http://localhost:8080/login.html

NB. Should be able to go to http://localhost:8080 but there's a bug with
how friend is configured in the project.


# Debugging

```shell
lein repl :headless
```

Connect via nrepl and then the following:

```clojure
(defn do-requires []
  (require '[jsk.workflow :as w])
  (require '[jsk.graph :as g])
  (require '[jsk.conductor :as c])
  (require '[jsk.ds :as ds])
  (require '[jsk.db :as db])
  (require '[clojure.pprint :as p]))

(do-requires)
(jsk.main/-main)
```


## License

Copyright © 2013 Amar Mehta

Distributed under the Eclipse Public License, the same as Clojure.
