(ns jsk.common.workflow
  (:require [taoensso.timbre :as log]
            [bouncer [core :as b] [validators :as v]]
            [jsk.common.util :as util]
            [jsk.common.data :as data]
            [jsk.common.db :as db]
            [korma.db :as k]
            [clojure.core.async :refer [put!]]))

(def ^:private out-chan (atom nil))

(defn init
  "Sets the channel to use when updates are made to workflows."
  [ch]
  (reset! out-chan ch))

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

(defn- save-graph [{:keys [workflow-id connections]} layout]
  (let [job->vertex (save-vertices workflow-id layout connections)]
    (doseq [{:keys [success? src-node-id tgt-node-id]} connections]
      (db/save-workflow-edge (job->vertex src-node-id)
                             (job->vertex tgt-node-id)
                             success?))))

(defn- save-workflow* [{:keys[workflow-id connections] :as w} layout user-id]
  (k/transaction
    (db/rm-workflow-graph workflow-id) ; rm existing and add new
    (let [workflow-id* (db/save-workflow w user-id)]
      (save-graph (assoc w :workflow-id workflow-id*) layout)
      workflow-id*)))

(defn save-workflow!
  "Saves the workflow to the database and the scheduler."
  [{:keys [layout workflow]} user-id]

  (log/debug "layout: " layout)
  (log/debug "wf: " workflow)
  (if-let [errors (validate-save workflow)]
    (util/make-error-response errors)
    (let [wf-id (save-workflow* workflow layout user-id)]
      (put! @out-chan {:msg :node-save :node-id wf-id :node-type-id data/workflow-type-id}) ; to notify conductor
      {:success? true :workflow-id wf-id})))

;-----------------------------------------------------------------------
; TODO: Move this handles both jobs and workflows and yet we have
;       two namespaces.
;-----------------------------------------------------------------------
(defn trigger-now
  "Puts a message on the conductor channel to trigger the node now."
  [node-id]
  (put! @out-chan {:msg :trigger-node
                   :node-id node-id}))

(defn abort-execution
  "Puts a message on the conductor channel to abort the execution."
  [execution-id user-id]
  (log/info "user " user-id "requests aborting execution " execution-id)
  (put! @out-chan {:msg :request-execution-abort
                   :execution-id execution-id}))


(defn abort-job
  "Puts a message on the conductor channel to abort the execution."
  [execution-id exec-vertex-id user-id]
  (log/info "user " user-id "requests aborting job" exec-vertex-id "within execution" execution-id)
  (put! @out-chan {:msg :request-job-abort
                   :execution-id execution-id
                   :exec-vertex-id exec-vertex-id}))


(defn resume-execution
  "Puts a message on the conductor channel to resume the execution."
  [execution-id exec-vertex-id user-id]
  (log/info "user " user-id "requests resuming job" exec-vertex-id "within execution" execution-id)
  (put! @out-chan {:msg :request-job-resume
                   :execution-id execution-id
                   :exec-vertex-id exec-vertex-id}))



