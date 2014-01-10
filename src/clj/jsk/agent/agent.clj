(ns jsk.agent.agent
  (:require
            [jsk.common.util :as util]
            [jsk.common.notification :as notification]
            [jsk.agent.ps :as ps]
            [jsk.common.conf :as conf]
            [jsk.common.messaging :as msg]
            [clojure.core.async :refer [put! <! go-loop chan]]
            [taoensso.timbre :as log]))


; map of exec-vertex-id to the msg sent to the conductor
(def finished-jobs (atom {}))

(defn- ch-put [ch data]
  (put! ch {:topic msg/root-topic :data data}))

(defn- register-with-conductor
  "Register this agent with the conductor."
  [ch agent-id]
  (ch-put ch {:msg :agent-registering :agent-id agent-id}))

(defn- when-job-finished
  "Adds it to the finished jobs atom and sends the msg to the conductor.
   Things are removed from finished-jobs when the conductor sends an ack."
  [{:keys[exec-vertex-id] :as msg} agent-id ch]

  (swap! finished-jobs #(assoc %1 exec-vertex-id msg))
  (ch-put ch msg))


(defmulti dispatch (fn [m _ _] (:msg m)))

; sent from conductor to have agents register themselves
(defmethod dispatch :agents-register [m agent-id ch]
  (register-with-conductor ch agent-id))

; sent from conductor, let conductor know of any finished
; jobs we haven't gotten acks for
(defmethod dispatch :agent-registered [m agent-id ch]
  (log/info "Agent is now registered.")

  (let [msgs (vals @finished-jobs)]
    (log/info "Finished jobs for which acks not received: " (count msgs))

    (when (-> msgs empty? not)
      (log/info "Unackd finished jobs: " msgs)
      (doseq [msg msgs]
        (ch-put ch msg)))))


; sent from conductor to have agents check in ie a heartbeat
(defmethod dispatch :heartbeat [m agent-id ch]
  (ch-put {:agent-id agent-id :msg :heartbeat-ack}))

(defmethod dispatch :run-job [{:keys [job exec-vertex-id execution-id exec-wf-id timeout]} agent-id ch]
  (future
   (let [{:keys[command-line execution-directory]} job
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
         (when-job-finished (assoc base-resp :success? success?) agent-id ch))
       (catch Exception ex
         (log/error ex)
         (when-job-finished (assoc base-resp :success? false) agent-id ch))))))


(defmethod dispatch :job-finished-ack [{:keys [execution-id exec-vertex-id]} agent-id ch]
  (log/debug "job-finished-ack for execution-id:" execution-id ", exec-vertex-id:" exec-vertex-id)
  (swap! finished-jobs #(dissoc %1 exec-vertex-id)))

(defmethod dispatch :abort-job [{:keys [execution-id exec-vertex-id]} agent-id ch]
  )

(defn init
  "Initializes this agent, sets up a message processing loop, registers with the
  conductor, and processes messages targeted for this agent."
  [host cmd-port req-port]

  (let [agent-id (util/jvm-instance-name)
        topics [msg/broadcast-topic (msg/make-topic agent-id)]
        bind? false
        write-ch (chan)]

    (log/info "Agent id is: " agent-id)
    (log/info "Listening to messages for topics: " topics)

    (msg/relay-writes write-ch host req-port false)
    (msg/relay-reads "request-processor" host cmd-port bind? topics #(dispatch %1 agent-id write-ch))
    (register-with-conductor write-ch agent-id)))
