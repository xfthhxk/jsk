(ns jsk.agent.agent
  (:require
            [jsk.common.util :as util]
            [jsk.common.notification :as notification]
            [jsk.agent.ps :as ps]
            [jsk.agent.events :as events]
            [jsk.common.conf :as conf]
            [jsk.common.messaging :as msg]
            [clojure.core.async :refer [put! <! go-loop chan]]
            [taoensso.timbre :as log]))


; aborted jobs set
(def aborted-jobs (atom #{}))
(def last-hb-rcvd-ts (atom 0))

(defn- ch-put [ch data]
  (put! ch {:topic msg/root-topic :data data}))

(defn- register-with-conductor
  "Register this agent with the conductor."
  [ch agent-id]
  (ch-put ch {:msg :agent-registering :agent-id agent-id}))

(defn- when-job-finished!
  "Adds it to the finished jobs atom and sends the msg to the conductor.
   Things are removed from when the conductor sends an ack."
  [{:keys[exec-vertex-id] :as jf-msg} agent-id ch]

  (if (contains? @aborted-jobs exec-vertex-id)
      (log/info exec-vertex-id "was aborted. Not sending job-finished msg.")
    (do 
      (events/persist! jf-msg)
      (ch-put ch jf-msg))))

(defn- conductor-alive?
  "Answers if the conductor has been alive in the last heartbeats-interval-ms"
  []
  (-> (util/now-ms) (- @last-hb-rcvd-ts) (< (conf/heartbeats-dead-after-ms))))


(defn- publish-unackd-msgs
  "Purge the events log file of ackd messages and for unackd messages
   (re)publish messages to the conductor."
  [ch]
  (let [alive? (conductor-alive?)
        unackd-msgs (events/purge-ackd!)]

    (when (and alive? (seq unackd-msgs))
      (log/info "Publishing" (count unackd-msgs) "messages without acks:" unackd-msgs)
      (reset! aborted-jobs #{})
      (doseq [msg unackd-msgs]
        (ch-put ch msg)))

    (if (and (not alive?) (seq unackd-msgs))
      (log/info (count unackd-msgs) "unackd messages, but conductor is not alive."))))


;-----------------------------------------------------------------------
; Multi method to handle dispatching of conductor messages
;-----------------------------------------------------------------------
(defmulti dispatch (fn [m _ _] (:msg m)))

;-----------------------------------------------------------------------
; Agents register broadcast from conductor
;-----------------------------------------------------------------------
(defmethod dispatch :agents-register [m agent-id ch]
  (register-with-conductor ch agent-id))

;-----------------------------------------------------------------------
; Conductor has registered this agent
; Time to publish events we haven't received acks for
;-----------------------------------------------------------------------
(defmethod dispatch :agent-registered [m agent-id ch]
  (log/info "Agent is now registered.")
  (reset! last-hb-rcvd-ts (util/now-ms))
  (publish-unackd-msgs ch))


;-----------------------------------------------------------------------
; Heartbeats (sent from conductor to have agents check in)
;-----------------------------------------------------------------------
(defmethod dispatch :heartbeat [m agent-id ch]
  (reset! last-hb-rcvd-ts (util/now-ms))
  (ch-put ch {:agent-id agent-id :msg :heartbeat-ack}))

;-----------------------------------------------------------------------
; Run job command from conductor
;-----------------------------------------------------------------------
(defmethod dispatch :run-job [{:keys [job exec-vertex-id execution-id exec-wf-id]} agent-id ch]
  (future
   (let [{:keys[command-line execution-directory timeout]} job
         log-file-name (str (conf/exec-log-dir) "/" exec-vertex-id ".log")
         ack-resp {:agent-id agent-id
                   :execution-id execution-id
                   :exec-vertex-id exec-vertex-id
                   :exec-wf-id exec-wf-id
                   :msg :run-job-ack}
         base-resp (assoc ack-resp :msg :job-finished)]

     (log/info "cmd-line: " command-line ", exec-dir: " execution-directory ", log-file: " log-file-name ", timeout:" timeout ", exec-wf-id:" exec-wf-id)
     ; send ack
     (ch-put ch ack-resp)

     (try
       (let [exit-code (ps/exec1 execution-id exec-vertex-id timeout command-line execution-directory log-file-name)
             success? (zero? exit-code)]
         (log/info "job exit-code " exit-code " success? " success?)
         (when-job-finished! (assoc base-resp :success? success?) agent-id ch))
       (catch Exception ex
         (log/error ex)
         (when-job-finished! (assoc base-resp :success? false) agent-id ch))))))


;-----------------------------------------------------------------------
; Abort job command from conductor
;-----------------------------------------------------------------------
(defmethod dispatch :abort-job [{:keys [execution-id exec-vertex-id] :as msg} agent-id ch]
  (log/info "abort-job msg rcvd: " msg)
  (let [reply-msg (merge {:agent-id agent-id} (select-keys msg [:execution-id :exec-vertex-id]))
        abort-msg (merge reply-msg {:msg :job-aborted :success? true})]
    ; send the ack
    (ch-put ch (merge reply-msg {:msg :abort-job-ack}))

    (ps/kill! execution-id exec-vertex-id)

    (events/persist! abort-msg)
    (swap! aborted-jobs conj exec-vertex-id)
    (ch-put ch abort-msg)))

;-----------------------------------------------------------------------
; Ack from conductor for the job FINISHED message we sent it
;-----------------------------------------------------------------------
(defmethod dispatch :job-finished-ack [{:keys [execution-id exec-vertex-id] :as msg} agent-id ch]
  (log/info "job-finished-ack for execution-id:" execution-id ", exec-vertex-id:" exec-vertex-id)
  (events/persist! msg))

;-----------------------------------------------------------------------
; Ack from conductor for the job ABORTED message we sent it
;-----------------------------------------------------------------------
(defmethod dispatch :job-aborted-ack [{:keys [execution-id exec-vertex-id] :as msg} agent-id ch]
  (log/info "job-aborted-ack for execution-id:" execution-id ", exec-vertex-id:" exec-vertex-id)
  (events/persist! msg)
  (swap! aborted-jobs disj exec-vertex-id))

;-----------------------------------------------------------------------
; These should never happen
;-----------------------------------------------------------------------
(defmethod dispatch :default [m agent-id ch]
  (log/error "No handler for " m))

(defn init
  "Initializes this agent, sets up a message processing loop, registers with the
  conductor, and processes messages targeted for this agent."
  [host cmd-port req-port agent-id]

  (let [topics [msg/broadcast-topic (msg/make-topic agent-id)]
        bind? false
        write-ch (chan)]

    (log/info "Agent id is: " agent-id)
    (log/info "Listening to messages for topics: " topics)

    (events/init!)
    (util/periodically "msg-log-purger" (conf/agent-msg-log-purge-ms) #(publish-unackd-msgs write-ch))

    (msg/relay-writes write-ch host req-port false)
    (msg/relay-reads "request-processor" host cmd-port bind? topics #(dispatch %1 agent-id write-ch))
    (register-with-conductor write-ch agent-id)))
