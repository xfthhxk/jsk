(ns jsk.common.workflow
  (:require [taoensso.timbre :as log]
            [bouncer [core :as b] [validators :as v]]
            [jsk.common.util :as util]
            [jsk.common.data :as data]
            [jsk.common.db :as db]
            [korma.db :as k]
            [clojure.core.async :refer [put!]]))

(defonce ^:private out-chan (atom nil))
(defonce ^:private ui-chan (atom nil))

(defn init
  "Sets the channel to use when updates are made to workflows."
  [ch ui-ch]
  (reset! out-chan ch)
  (reset! ui-chan ui-ch))

;-----------------------------------------------------------------------
; Workflows lookups
;-----------------------------------------------------------------------
(defn ls-workflows
  "Lists all workflows"
  []
  (db/ls-workflows))

(defn enabled-workflows
  "Gets all active workflows."
  []
  (db/enabled-workflows))

(defn get-workflow
  "Gets a workflow for the id specified"
  [id]
  (db/get-workflow id))

(defn get-workflow-name
  "Answers with the workflow name for the workflow id, otherwise nil if no such workflow."
  [id]
  (db/get-workflow-name id))

(defn get-workflow-by-name
  "Gets a workflow by name if one exists otherwise returns nil"
  [nm]
  (db/get-workflow-by-name nm))

(defn workflow-name-exists?
  "Answers true if workflow name exists"
  [nm]
  (db/workflow-name-exists? nm))

(defn unique-name? [id wname]
  (if-let [j (get-workflow-by-name wname)]
    (= id (:workflow-id j))
    true))


(defn workflow-nodes
  "Returns a seq of maps."
  [id]
  (db/get-workflow-graph id))



(defn validate-save [{:keys [workflow-id] :as w}]
  (-> w
      (b/validate
       :workflow-name [v/required [(partial unique-name? workflow-id) :message "Workflow name must be unique."]])
      first))

;-----------------------------------------------------------------------
; Saving the graph
;-----------------------------------------------------------------------
(defn- key-layout-by-id [layout]
  (reduce (fn [ans m]
            (assoc ans (:node-id m) (:css-text m)))
          {}
          layout))

; saves vertices to db and return a map of node-id to vertex-id
(defn- save-vertices [workflow-id layout connections]
  (let [vv (reduce (fn[ans c]
                     (-> ans (conj (:src-node-id c))
                             (conj (:tgt-node-id c)))) #{} connections)
        layout-map (key-layout-by-id layout)]
    (reduce (fn[m v]
              (assoc m v (db/save-workflow-vertex workflow-id v (layout-map v))))
            {}
            vv)))

(defn- save-unconnected-vertices [workflow-id layout node-ids]
  (let [layout-map (key-layout-by-id layout)]
    (doseq [n-id node-ids]
      (db/save-workflow-vertex workflow-id n-id (layout-map n-id)))))

(defn- save-graph [{:keys [workflow-id connections unconnected]} layout]
  (let [job->vertex (save-vertices workflow-id layout connections)]
    (doseq [{:keys [success? src-node-id tgt-node-id]} connections]
      (db/save-workflow-edge (job->vertex src-node-id)
                             (job->vertex tgt-node-id)
                             success?))
    (save-unconnected-vertices workflow-id layout unconnected)))

(defn- save-workflow* [{:keys[workflow-id connections] :as w} layout user-id]
  (k/transaction
    (db/rm-workflow-graph workflow-id) ; rm existing and add new
    (let [workflow-id* (db/save-workflow w user-id)]
      (save-graph (assoc w :workflow-id workflow-id*) layout)
      workflow-id*)))


(defn- notify [{:keys [workflow-id workflow-name node-directory-id is-enabled]}]
  (let [schedule-count (-> workflow-id db/schedules-for-node count)
        info-msg {:msg :node-save
                  :node-id workflow-id
                  :node-type-id data/workflow-type-id}
        event-msg {:crud-event :node-save
                   :node-id workflow-id
                   :node-type-id data/workflow-type-id
                   :node-name workflow-name 
                   :enabled? is-enabled
                   :scheduled? (> schedule-count 0)
                   :node-directory-id node-directory-id}]
    ;; to notify conductor
    (put! @out-chan info-msg)
    ;; to notify ui
    (put! @ui-chan event-msg)))

(defn save-workflow!
  "Saves the workflow to the database and the scheduler."
  [{:keys [layout workflow]} user-id]

  (log/debug "layout: " layout)
  (log/debug "wf: " workflow)
  (if-let [errors (validate-save workflow)]
    (util/make-error-response errors)
    (let [wf-id (save-workflow* workflow layout user-id)]
      (notify (merge workflow {:workflow-id wf-id}))
      {:success? true :workflow-id wf-id})))

(defn update-enabled-status
  "Updates the is-enabled status for the job to be enabled?"
  [wf-id enabled? user-id]
  (db/set-node-enabled wf-id enabled?)
  (let [w (get-workflow wf-id)]
    (notify w)))

;-----------------------------------------------------------------------
; TODO: Move this handles both jobs and workflows and yet we have
;       two namespaces.
;-----------------------------------------------------------------------
(defn trigger-now
  "Puts a message on the conductor channel to trigger the node now."
  [node-id]
  (put! @out-chan {:msg :trigger-node
                   :node-id node-id}))

(defn force-success
  "Places a force success message to the conductor."
  [execution-id exec-vertex-id user-id]
  (log/info "user " user-id "requests force success for execution " execution-id " and exec-vertex-id " exec-vertex-id)
  (put! @out-chan {:msg :request-force-success
                   :execution-id execution-id
                   :exec-vertex-id exec-vertex-id}))

(defn abort-execution
  "Puts a message on the conductor channel to abort the execution."
  [execution-id user-id]
  (log/info "user " user-id "requests aborting execution " execution-id)
  (put! @out-chan {:msg :request-execution-abort
                   :execution-id execution-id}))

(defn pause-execution
  "Puts a message on the conductor channel to abort the execution."
  [execution-id user-id]
  (log/info "user " user-id "requests pausing execution " execution-id)
  (put! @out-chan {:msg :request-execution-pause
                   :execution-id execution-id}))

(defn resume-execution
  "Puts a message on the conductor channel to abort the execution."
  [execution-id user-id]
  (log/info "user " user-id "requests resuming execution " execution-id)
  (put! @out-chan {:msg :request-execution-resume
                   :execution-id execution-id}))


(defn abort-job
  "Puts a message on the conductor channel to abort the execution."
  [execution-id exec-vertex-id user-id]
  (log/info "user " user-id "requests aborting job" exec-vertex-id "within execution" execution-id)
  (put! @out-chan {:msg :request-job-abort
                   :execution-id execution-id
                   :exec-vertex-id exec-vertex-id}))

(defn pause-job
  "Puts a message on the conductor channel to abort the execution."
  [execution-id exec-vertex-id user-id]
  (log/info "user " user-id "requests pausing job" exec-vertex-id "within execution" execution-id)
  (put! @out-chan {:msg :request-job-pause
                   :execution-id execution-id
                   :exec-vertex-id exec-vertex-id}))

(defn resume-job
  "Puts a message on the conductor channel to abort the execution."
  [execution-id exec-vertex-id user-id]
  (log/info "user " user-id "requests resuming job" exec-vertex-id "within execution" execution-id)
  (put! @out-chan {:msg :request-job-resume
                   :execution-id execution-id
                   :exec-vertex-id exec-vertex-id}))


(defn restart-execution
  "Puts a message on the conductor channel to resume the execution."
  [execution-id exec-vertex-id user-id]
  (log/info "user " user-id "requests restart job" exec-vertex-id "within execution" execution-id)
  (put! @out-chan {:msg :request-job-restart
                   :execution-id execution-id
                   :exec-vertex-id exec-vertex-id}))



(defn new-empty-workflow!
  "Makes a new workflow with default values for everything. Used by the explorer style ui."
  [dir-id user-id]
  (let [wf {:workflow-id -1
            :workflow-name (str "Workflow " (util/now-ms))
            :workflow-desc ""
            :node-directory-id dir-id
            :is-enabled true}]
    (save-workflow! {:workflow wf :layout []} user-id)))
