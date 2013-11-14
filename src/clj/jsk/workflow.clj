(ns jsk.workflow
  (:require [taoensso.timbre :as timbre :refer (debug info warn error)]
            [bouncer [core :as b] [validators :as v]]
            [clojure.stacktrace :as st]
            [jsk.graph :as g]
            [jsk.quartz :as q]
            [jsk.schedule :as s]
            [jsk.util :as ju]
            [jsk.db :as db]
            [clojure.core.async :refer [put!]])
  (:use [korma core db]
        [swiss-arrows core]))

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
            (assoc ans (:job-id m) (:css-text m)))
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
  (transaction
    (db/rm-workflow-graph workflow-id) ; rm existing and add new
    (let [workflow-id* (db/save-workflow w user-id)]
      (save-graph (assoc w :workflow-id workflow-id*) layout)
      {:success? true :workflow-id workflow-id})))

(defn save-workflow!
  "Saves the workflow to the database and the scheduler."
  [{:keys [layout workflow]} user-id]
  (if-let [errors (validate-save workflow)]
    (ju/make-error-response errors)
    (save-workflow* workflow layout user-id)))

;-----------------------------------------------------------------------
; Produce the graph and make usable for quartz.
;-----------------------------------------------------------------------
(defn- data->digraph
  "Generates a digraph."
  [data]
  (reduce (fn[graph {:keys[from-node-id to-node-id]}]
            (g/add-edge graph from-node-id to-node-id))
            {} data))

(defn- data->node-table
  "Generates a map keyed by node ids. The value is another map
   with two keys :success and :fail each which points to a set
   of nodes to execute when the job succeeds or fails.
   e.g. {1 {true #{2 3} false #{4}}}

   input: {:from-node-id 1 :to-node-id 2 :success true}
   output: {1 {true #{2}}}

  NB. Only jobs that kick off other jobs will be keys."
  [data]
  (reduce (fn[ans {:keys[from-node-id to-node-id success]}]
            (update-in ans
                       [from-node-id success]
                       (fn [s id] (if s (conj s id) #{id})) to-node-id)) ; s is a set or nil
          {}
          data))



(defn workflow-data
  "For the given workflow id, answers with a map with the keys
   :roots and :table.  :roots is a set of node ids which represent
   the start of the workflow.  :table is a map keyed by node ids.
   example table {1 {true #{2 3} false #{4}}} where 1 is the node-id,
   the set #{2 3} are the next nodes to execute when node 1 finishes
   successfully. #{4} is to be executed when 1 errors.
   Asserts the workflow is an acyclic digraph; otherwise, it may
   never terminate."
  [id]
  (let [data (workflow-nodes id)
        digraph (data->digraph data)
        tbl (data->node-table data)]

    (if (-> digraph g/acyclic? not)
      (throw (IllegalStateException. "Cyclic graphs disallowed")))

    {:roots (g/roots digraph) :table tbl}))


;-----------------------------------------------------------------------
; Trigger now
;-----------------------------------------------------------------------
(defn trigger-now [wf-id]
  (info "Triggering workflow right now with id " wf-id)
  (try
    (let [wf-data (workflow-data wf-id)]
      (q/trigger-workflow-now wf-id wf-data)
      true)
    (catch Exception e
      (error e)
      false)))









