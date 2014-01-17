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
  - conductor: Central server managing jobs/workflows triggering
  - agent: Separate process likely on another machine than the conductor which actually runs jobs.

## Usage

* Start h2 db
* Run the sql script located at sql/schema.sql
    - make changes to app_user to put in your email id
* Update resources/conf/jsk-conf.clj as needed

```shell
mkdir log
touch log/jsk.log
tail -f log/jsk.log
```

# Running / Debugging via nRepl

```shell
# To run the conductor
lein run --mode conductor --hostname localhost --cmd-port 9000 --status-port 9001 --nrepl-port 7001

# To run the web app
lein run --mode console --hostname localhost --cmd-port 9000 --status-port 9001 --web-app-port 8080 --nrepl-port 7002
```
# To run the agent
lein run --mode agent --hostname localhost --cmd-port 9000 --status-port 9001 --nrepl-port 7003 --agent-name agent-1 
lein run --mode agent --hostname localhost --cmd-port 9000 --status-port 9001 --nrepl-port 7004 --agent-name second-agent

## License

Copyright © 2013 Amar Mehta

Distributed under the Eclipse Public License, the same as Clojure.
