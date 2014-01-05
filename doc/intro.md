# Introduction to jsk

TODO: write [great documentation](http://jacobian.org/writing/great-documentation/what-to-write/)

## Conductor / Agent protocol

# Messaging background
Nanomsg is used for communication.
nanomsg subscriptions seem to be handled by looking at the message prefix
messages are structured: "${topic}\u0000${edn-string}"
Messages sent to all agents use the topic "broadcast"
All data in the edn-string is a map.
Each map has the key :msg which is the type of message being sent.

# Conductor startup
Conductor sets up a nanomsg pub socket which it publishes messages on.
Conductor sets up a nanomsg sub socket which it uses for listening to messages from agents and the web app.
Conductor subscribes to everything on the sub socket.
Starts heartbeats on the pub socket using the "broadcast" topic and the :heartbeat msg.
Starts processing messages off of the sub socket.

Publishes a broadcast message to have agents register.
Keeps broadcasting the agent register message once a second until at least one agent is connected.
At least one agent is required to execute the jobs.

Next populates and starts quartz with jobs/workflows from the database.

# Conductor / Agent protocol

## Agent is started before conductor.
Conductor sends a broadcast message {:msg agents-register}
Agent responds with {:msg agent-registering :agent-id "pid@host"}

## Conductor is started first.
Conductor keeps broadcasting message {:msg agents-register} every 1000ms until at least one agent registers.


## Running a job (No software/network issues -- The ideal scenario)
Conductor sends a :msg with :run-job and the following keys:
 :job a map of job data
 :execution-id
 :exec-vertex-id This is always a unique value
 :exec-wf-id     Used for tracking things by conductor (Shouldn't have to send it. To be fixed in refactoring)
 :timeout        timeout value in seconds

When the agent gets the :run-job message, it sends a :run-job-ack to the conductor and proceeds to run the job.
When the job finishes the agent records the outcome in its memory and sends a :job-finished message with the
following keys:
  :agent-id
  :execution-id
  :exec-vertex-id
  :exec-wf-id
  :success? true/false

Conductor sends a :job-finished-ack to the agent that sent the :job-finished msg.
The agent removes the job status from its memory.

### Conductor sends the :run-job message and dies.
  * Agent sends :run-job-ack and starts and finishes the job.
  * Since the agent does not get the :job-finished-ack from the conductor, it will have the jobs outcome in memory
  * Conductor is started, loads unfinished executions.
  * Conductor broadcasts agents register.
  * Agent registers
  * Conductor starts quartz.
  * Agent forwards jobs for which it has not received :job-finished-ack messages for
  * Conductor sends :job-finished-ack messages for previously finished jobs.
  * Agent removes the finished jobs from memory.
  * Conductor must be idempotent with receivng acks multiple times.
  * Also need to handle execution-ids which won't be in memory if the agent sends one.


### Conductor sends the :run-job message, agent has died.
  * Agent will not respond to heartbeats if this is the case.
  * After n seconds of no heartbeats, conductor marks the agent as dead and jobs that the agent had as unknown.
  * Users will have to retrigger jobs after agent is back online.
  * Agent when starting will check its log to see what finished jobs it hasn't received acks for and resend to conductor.

### Conductor sends the :run-job message, agent sends :run-job-ack but switch dies, agent doesn't get the message.
  * Agent will not respond to heartbeats if this is the case.
  * After n seconds of no heartbeats, conductor marks the agent as dead and jobs that the agent had as unknown state.
  * If the agent finishes the job, sends the :job-finished msg and does not receive the :job-finished-ack, it will remain in memory.
    * Agent can republish to the conductor once the switch is fixed and heartbeats flow again.
  * If the agent dies while processing the job or after finishing the job but before switch is fixed, then the jobs status is unknown.
    * Agent should write the job-finished to disk.
    * On startup replay the log to see what needs to be published again when restarted.
    * Clear the log once a minute so it's not a huge log etc.
    * When agent starts job it should record it to disk too. So if the agent is killed in the middle
      on startup it can look at the log and see the job was started but not finished and send
      a job failed message to the conductor.


















