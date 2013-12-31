(ns jsk.agent
  (:require
            [jsk.util :as util]
            [jsk.ps :as ps]
            [jsk.conf :as conf]
            [jsk.messaging :as msg]
            [nanomsg :as nn]
            [taoensso.timbre :as log]
            [clojure.string :as string]
            [clojure.core.async :refer [<!! thread go put!]]))

; topic is emtpy string because conductor listens to everything
(def ^:private pub-topic "")


(defn- register-with-conductor
  "Register this agent with the conductor."
  [sock agent-id]
  (msg/publish sock pub-topic {:msg :agent-registering :agent-id agent-id}))


(defmulti dispatch (fn [m _ _] (:msg m)))

(defmethod dispatch :noop [_ _ _]
  (log/debug "encountered noop"))

; sent from conductor to have agents register themselves
(defmethod dispatch :agents-register [m agent-id sock]
  (register-with-conductor sock agent-id))

; sent from conductor to have agents check in ie a heartbeat
(defmethod dispatch :heartbeat [m agent-id sock]
  (msg/publish sock pub-topic {:agent-id agent-id :msg :heartbeat-ack}))

(defmethod dispatch :run-job [{:keys [job exec-vertex-id execution-id timeout]} agent-id sock]
  (future
   (let [{:keys[command-line execution-directory]} job
         log-file-name (str (conf/exec-log-dir) "/" exec-vertex-id ".log")
         ack-resp {:agent-id agent-id
                   :execution-id execution-id
                   :exec-vertex-id exec-vertex-id
                   :msg :run-job-ack}
         base-resp (assoc ack-resp :msg :job-finished)]

     (log/info "cmd-line: " command-line ", exec-dir: " execution-directory ", log-file: " log-file-name ", timeout:" timeout)
     ; send ack
     (msg/publish sock pub-topic ack-resp)

     (try
       (let [exit-code (ps/exec1 execution-id exec-vertex-id timeout command-line execution-directory log-file-name)]
         (msg/publish sock pub-topic (assoc base-resp :status exit-code)))
       (catch Exception ex
         (log/error ex)
         (msg/publish sock pub-topic (assoc base-resp :status 1)))))))

(defn init
  "Initializes this agent, sets up a message processing loop, registers with the
  conductor, and processes messages targeted for this agent."
  [host cmd-port req-port]

  (let [agent-id (util/uuid)
        topics ["broadcast" agent-id]
        sock (msg/make-socket "tcp" host cmd-port false :sub)
        pub-sock (msg/make-socket "tcp" host req-port false :pub)]

    (log/info "Agent id is: " agent-id)
    (log/info "Listening to messages for topics: " topics)

    (msg/subscribe sock topics)

    ; on startup agent needs to register with the conductor
    ; so the conductor knows it is available
    (register-with-conductor pub-sock agent-id)

    ; main infinite message loop
    (loop [data (msg/read-pub-data sock)]
      (dispatch data agent-id pub-sock)
      (recur (msg/read-pub-data sock)))

    ; never get here but cleanup anyway
    (msg/close! [sock pub-sock])))











