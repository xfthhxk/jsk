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


; map of exec-vertex-id to the msg sent to the conductor
(def finished-jobs (atom {}))

(defn- when-job-finished
  "Adds it to the finished jobs atom and sends the msg to the conductor.
   Things are removed from finished-jobs when the conductor sends an ack."
  [{:keys[exec-vertex-id] :as msg} agent-id sock]

  (swap! finished-jobs #(assoc %1 exec-vertex-id msg))
  (msg/publish sock pub-topic msg))


(defmulti dispatch (fn [m _ _] (:msg m)))

(defmethod dispatch :noop [_ _ _]
  (log/debug "encountered noop"))

; sent from conductor to have agents register themselves
(defmethod dispatch :agents-register [m agent-id sock]
  (register-with-conductor sock agent-id))

; sent from conductor to have agents check in ie a heartbeat
(defmethod dispatch :heartbeat [m agent-id sock]
  (msg/publish sock pub-topic {:agent-id agent-id :msg :heartbeat-ack}))

(defmethod dispatch :run-job [{:keys [job exec-vertex-id execution-id exec-wf-id timeout]} agent-id sock]
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
     (msg/publish sock pub-topic ack-resp)

     (try
       (let [exit-code (ps/exec1 execution-id exec-vertex-id timeout command-line execution-directory log-file-name)
             success? (zero? exit-code)]
         (msg/publish sock pub-topic (assoc base-resp :success? success?)))
       (catch Exception ex
         (log/error ex)
         (msg/publish sock pub-topic (assoc base-resp :success? false)))))))


(defmethod dispatch :job-finished-ack [{:keys [execution-id exec-vertex-id]} agent-id sock]
  (log/debug "job-finished-ack for execution-id:" execution-id ", exec-vertex-id:" exec-vertex-id)
  (swap! finished-jobs #(dissoc %1 exec-vertex-id)))

(defmethod dispatch :abort-job [{:keys [execution-id exec-vertex-id]} agent-d sock]
  )

(defn init
  "Initializes this agent, sets up a message processing loop, registers with the
  conductor, and processes messages targeted for this agent."
  [host cmd-port req-port]

  (let [agent-id (util/jvm-instance-name)
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











