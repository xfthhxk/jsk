(ns jsk.conductor.execution-setup
  (:require [taoensso.timbre :as log]
            [jsk.common.graph :as graph]
            [jsk.common.workflow :as wf]
            [jsk.conductor.execution-model :as model]
            [jsk.common.util :as util]
            [jsk.common.db :as db]))


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

    (log/debug "root-wfs: " root-wfs)

    (assert (= 1 (count root-wfs)) (str "Only 1 wf can be the root wf. Got: " root-wfs))

    (-> tbl
        (model/set-root-workflow root-wf)
        (model/add-workflows (map :execution-workflow-id data)))))

(defn- populate-wf-mappings [tbl data]
  (reduce (fn[ans {:keys[workflow-id execution-workflow-id]}]
            (model/add-workflow-mapping ans execution-workflow-id workflow-id))
          tbl
          data))

(defn- populate-vertices
  "Populates the execution info tbl by supplying it with vertices
   pulled from data. data is a seq of maps."
  [tbl data]
  (let [v-fn (juxt :src-exec-vertex-id :dest-exec-vertex-id)
        vv (into #{} (mapcat v-fn data))]
  (model/add-vertices tbl vv)))

(defn- add-deps
  "Adds in dependency information in tbl pulled from data."
  [tbl data]
  (reduce (fn [ans {:keys[execution-workflow-id src-exec-vertex-id dest-exec-vertex-id success]}]
            (model/add-dependency ans execution-workflow-id src-exec-vertex-id dest-exec-vertex-id success))
          tbl
          data))

(defn- set-vertex-attrs
  "Sets the vertex attributes in tbl from data."
  [tbl data]
  (reduce (fn[ans {:keys[src-id src-exec-vertex-id src-name src-type
                         dest-id dest-exec-vertex-id dest-name dest-type
                         workflow-id execution-workflow-id]}]
            (-> ans
               (model/set-vertex-attrs src-exec-vertex-id src-id src-name src-type workflow-id execution-workflow-id)
               (model/set-vertex-attrs dest-exec-vertex-id dest-id dest-name dest-type workflow-id execution-workflow-id)))
          tbl
          data))


(defn- use-previous-finalization* [tbl data vertex-key runs-wf-key]
  (reduce (fn[ans m]
            (let [vertex (vertex-key m)
                  runs-wf (runs-wf-key m)]
              (if runs-wf
                (model/set-vertex-runs-workflow ans vertex runs-wf)
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
  (-> (model/new-execution-table)
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
      (model/finalize tbl)
      (use-previous-finalization tbl data))))


(defn- data->digraph
  "Generates a digraph."
  [data]
  (reduce (fn[graph {:keys[src-exec-vertex-id dest-exec-vertex-id]}]
            (graph/add-edge graph src-exec-vertex-id dest-exec-vertex-id))
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
  (let [data (wf/workflow-nodes id)
        digraph (data->digraph data)
        tbl (data->exec-tbl data)]

    (if (-> digraph graph/acyclic? not)
      (throw (IllegalStateException. "Cyclic graphs disallowed")))

    {:roots (graph/roots digraph) :table tbl}))

(defn- workflow-execution-data
  "Fetches a map with keys :execution-id :info. :info is the workflow
   execution data and returns it as a data structure which conforms
   to the IExecutionTable protocol."
  [exec-id initial-run?]
  (let [data (db/get-execution-graph exec-id)
        tbl (data->exec-tbl data initial-run?)]
    (log/debug "tbl is " tbl)
    {:execution-id exec-id :info tbl}))


(defn- populate-synthetic-wf-data [{:keys[execution-id exec-wf-id wf-id exec-vertex-id job-id job-nm node-type]}]
    {:execution-id execution-id
     :info
      (-> (model/new-execution-table)
          (model/add-workflows [exec-wf-id])
          (model/add-workflow-mapping exec-wf-id wf-id)
          (model/set-root-workflow exec-wf-id)
          (model/add-vertices [exec-vertex-id])
          (model/set-vertex-attrs exec-vertex-id job-id job-nm node-type wf-id exec-wf-id)
          (model/finalize))})

(defn resume-workflow-execution-data
  "Fetches an existing execution's data."
  [exec-id]
  (if (db/synthetic-workflow-execution? exec-id)
    (populate-synthetic-wf-data (db/synthetic-workflow-resumption exec-id))
    (workflow-execution-data exec-id false)))


(defn setup-execution [wf-id]
  (let [exec-id (db/new-execution! wf-id)
        {:keys[info] :as ans} (workflow-execution-data exec-id true)
        vertex-wf-map (model/vertex-workflow-to-run-map info)]
    (db/set-vertex-runs-execution-workflow-mapping vertex-wf-map)
    ans))

(defn setup-synthetic-execution [job-id]
  (populate-synthetic-wf-data (db/synthetic-workflow-started job-id)))
