(ns jsk.workflow
  (:require [taoensso.timbre :as timbre :refer (debug info warn error)]
            [bouncer [core :as b] [validators :as v]]
            [clojure.stacktrace :as st]
            [jsk.graph :as g]
            [jsk.ds :as ds]
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
  (transaction
    (db/rm-workflow-graph workflow-id) ; rm existing and add new
    (let [workflow-id* (db/save-workflow w user-id)]
      (save-graph (assoc w :workflow-id workflow-id*) layout)
      {:success? true :workflow-id workflow-id})))

(defn save-workflow!
  "Saves the workflow to the database and the scheduler."
  [{:keys [layout workflow]} user-id]
  (debug "layout: " layout)
  (debug "wf: " workflow)
  (if-let [errors (validate-save workflow)]
    (ju/make-error-response errors)
    (save-workflow* workflow layout user-id)))

;-----------------------------------------------------------------------
; Produce the graph and make usable for quartz.
;-----------------------------------------------------------------------
(defn- parse-root-exec-wf [data]
  (->> data
       (filter :is-root-wf)
       (map :execution-workflow-id)
       distinct))


(defn- populate-exec-wfs [tbl data]
  (let [root-wfs (parse-root-exec-wf data)
        root-wf (first root-wfs)]

    (debug "root-wfs: " root-wfs)

    (assert (= 1 (count root-wfs)) (str "Only 1 wf can be the root wf. Got: " root-wfs))

    (-> tbl
        (ds/set-root-workflow root-wf)
        (ds/add-workflows (map :execution-workflow-id data)))))

(defn- populate-wf-mappings [tbl data]
  (reduce (fn[ans {:keys[workflow-id execution-workflow-id]}]
            (ds/add-workflow-mapping ans execution-workflow-id workflow-id))
          tbl
          data))

(defn- populate-vertices
  "Populates the execution info tbl by supplying it with vertices
   pulled from data. data is a seq of maps."
  [tbl data]
  (let [v-fn (juxt :src-exec-vertex-id :dest-exec-vertex-id)
        vv (into #{} (mapcat v-fn data))]
  (ds/add-vertices tbl vv)))

(defn- add-deps
  "Adds in dependency information in tbl pulled from data."
  [tbl data]
  (reduce (fn [ans {:keys[execution-workflow-id src-exec-vertex-id dest-exec-vertex-id success]}]
            (ds/add-dependency ans execution-workflow-id src-exec-vertex-id dest-exec-vertex-id success))
          tbl
          data))

(defn- set-vertex-attrs
  "Sets the vertex attributes in tbl from data."
  [tbl data]
  (reduce (fn[ans {:keys[src-id src-exec-vertex-id src-name src-type
                         dest-id dest-exec-vertex-id dest-name dest-type
                         workflow-id execution-workflow-id]}]
            (-> ans
               (ds/set-vertex-attrs src-exec-vertex-id src-id src-name src-type workflow-id execution-workflow-id)
               (ds/set-vertex-attrs dest-exec-vertex-id dest-id dest-name dest-type workflow-id execution-workflow-id)))
          tbl
          data))


(defn- use-previous-finalization* [tbl data vertex-key runs-wf-key]
  (reduce (fn[ans m]
            (let [vertex (vertex-key m)
                  runs-wf (runs-wf-key m)]
              (if runs-wf
                (ds/set-vertex-runs-workflow ans vertex runs-wf)
                ans)))
          tbl
          data))

(defn- use-previous-finalization
  "Sets the workflows to run for the workflow nodes."
  [tbl data]
  (-> tbl
      (use-previous-finalization* data :src-exec-vertex-id  :src-runs-execution-workflow-id)
      (use-previous-finalization* data :dest-exec-vertex-id :dest-runs-execution-workflow-id)))

(defn- data->exec-tbl*
  "data is a seq of maps which is turned into an execution info table."
  [data]
  (-> (ds/new-execution-table)
      (populate-wf-mappings data)
      (populate-exec-wfs data)
      (populate-vertices data)
      (add-deps data)
      (set-vertex-attrs data)))


(defn- data->exec-tbl
  "Has to make a distinction between a brand new execution and resuming an existing execution.
   When resuming use the data available in the DB already."
  [data initial-run?]
  (let [tbl (data->exec-tbl* data)]
    (if initial-run?
      (ds/finalize tbl)
      (use-previous-finalization tbl data))))


(defn- data->digraph
  "Generates a digraph."
  [data]
  (reduce (fn[graph {:keys[src-exec-vertex-id dest-exec-vertex-id]}]
            (g/add-edge graph src-exec-vertex-id dest-exec-vertex-id))
            {} data))

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
        tbl (data->exec-tbl data)]

    (if (-> digraph g/acyclic? not)
      (throw (IllegalStateException. "Cyclic graphs disallowed")))

    {:roots (g/roots digraph) :table tbl}))

(defn- workflow-execution-data
  "Fetches a map with keys :execution-id :info. :info is the workflow
   execution data and returns it as a data structure which conforms
   to the IExecutionTable protocol."
  [exec-id initial-run?]
  (let [data (db/get-execution-graph exec-id)
        tbl (data->exec-tbl data initial-run?)]
    (debug "tbl is " tbl)
    {:execution-id exec-id :info tbl}))

(defn resume-workflow-execution-data
  "Fetches an existing execution's data."
  [exec-id]
  (workflow-execution-data exec-id false))


(defn setup-execution [wf-id]
  (let [exec-id (db/new-execution! wf-id)
        {:keys[info] :as ans} (workflow-execution-data exec-id true)
        vertex-wf-map (ds/vertex-workflow-to-run-map info)]
    (db/set-vertex-runs-execution-workflow-mapping vertex-wf-map)
    ans))

(defn setup-synthetic-execution [job-id]
  (let [{:keys [execution-id wf-id exec-wf-id exec-vertex-id
                status job-nm node-type]} (db/synthetic-workflow-started job-id)]

    {:execution-id execution-id
     :info
      (-> (ds/new-execution-table)
          (ds/add-workflows [exec-wf-id])
          (ds/add-workflow-mapping exec-wf-id wf-id)
          (ds/set-root-workflow exec-wf-id)
          (ds/add-vertices [exec-vertex-id])
          (ds/set-vertex-attrs exec-vertex-id job-id job-nm node-type wf-id exec-wf-id)
          (ds/finalize))}))








