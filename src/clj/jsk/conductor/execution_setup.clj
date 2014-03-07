(ns jsk.conductor.execution-setup
  (:require [taoensso.timbre :as log]
            [clojure.string :as string]
            [jsk.common.data :as data]
            [jsk.common.graph :as graph]
            [jsk.common.workflow :as wf]
            [jsk.conductor.execution-model :as exm]
            [jsk.conductor.cache :as cache]
            [jsk.common.util :as util]
            [jsk.common.db :as db]))

;; there's a bunch of (if dest-exec-vertex-id kind of code which
;; is required for workflows which have things which execute in parallel

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

    (assert (= 1 (count root-wfs)) (str "Only 1 wf can be the root wf, but have " (count root-wfs)))

    (-> tbl
        (exm/set-root-workflow root-wf)
        (exm/add-workflows (map :execution-workflow-id data)))))

(defn- populate-wf-mappings [tbl data]
  (reduce (fn[ans {:keys[workflow-id execution-workflow-id]}]
            (exm/add-workflow-mapping ans execution-workflow-id workflow-id))
          tbl
          data))

(defn- populate-vertices
  "Populates the execution info tbl by supplying it with vertices
   pulled from data. data is a seq of maps."
  [tbl data]
  (let [vv (into #{} (->> data
                          (mapcat (juxt :src-exec-vertex-id :dest-exec-vertex-id))
                          (filter identity)))]
    (log/info "populate-vertices are " (string/join "," vv))
    (exm/add-vertices tbl vv)))

(defn- add-deps
  "Adds in dependency information in tbl pulled from data."
  [tbl data]
  (reduce (fn [ans {:keys[execution-workflow-id src-exec-vertex-id dest-exec-vertex-id success]}]
            (if dest-exec-vertex-id
              (exm/add-dependency ans execution-workflow-id src-exec-vertex-id dest-exec-vertex-id success)
              (exm/add-non-dependency ans execution-workflow-id src-exec-vertex-id)))
          tbl
          data))

(defn- set-vertex-attrs
  "Sets the vertex attributes in tbl from data."
  [tbl data]
  (reduce (fn[ans {:keys[src-id src-exec-vertex-id src-name src-type
                         dest-id dest-exec-vertex-id dest-name dest-type
                         workflow-id execution-workflow-id]}]
            (let [ans' (exm/set-vertex-attrs ans src-exec-vertex-id src-id src-name src-type workflow-id execution-workflow-id)]
              (if dest-exec-vertex-id
                (exm/set-vertex-attrs ans' dest-exec-vertex-id dest-id dest-name dest-type workflow-id execution-workflow-id)
                ans')))
          tbl
          data))


(defn- use-previous-finalization* [tbl data vertex-key runs-wf-key]
  (reduce (fn[ans m]
            (let [vertex (vertex-key m)
                  runs-wf (runs-wf-key m)]
              (if runs-wf
                (exm/set-vertex-runs-workflow ans vertex runs-wf)
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
  (-> (exm/new-execution-table)
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
      (exm/finalize tbl)
      (use-previous-finalization tbl data))))


(defn- data->digraph
  "Generates a digraph."
  [data]
  (reduce (fn[graph {:keys[src-exec-vertex-id dest-exec-vertex-id]}]
            (if dest-exec-vertex-id
              (graph/add-edge graph src-exec-vertex-id dest-exec-vertex-id)
              graph))
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

(defn- assoc-agent-to-vertices
  "Assoc agent-id to each job vertex."
  [model node-cache]
  (reduce (fn [mdl v-id]
            (let [node-id (-> mdl (exm/vertex-attrs v-id) :node-id)
                  agent-name (cache/agent-name-for-job-id node-cache node-id)]
              (exm/assoc-agent-name mdl v-id agent-name)))
          model
          (exm/job-vertices model)))

(defn- assoc-alerts-to-vertices
  "vertex-alerts-map is a map of exec-vertex-ids to sets of alert-ids"
  [model vertex-alerts-map]
  (reduce (fn [mdl v-id]
            (exm/assoc-alerts mdl v-id (vertex-alerts-map v-id)))
          model
          (keys vertex-alerts-map)))

(defn- workflow-execution-data
  "Fetches a map with keys :execution-id :info. :info is the workflow
   execution data and returns it as a data structure which conforms
   to the IExecutionTable protocol."
  [exec-id initial-run? wf-id wf-nm node-cache]
  (let [data (db/get-execution-graph exec-id)
        model (data->exec-tbl data initial-run?)
        vertex-alerts-map (db/execution-vertices-with-alerts exec-id)
        root-wf-alert-ids (db/alert-ids-for-node wf-id)]
    {:execution-id exec-id
     :model
     (-> model
         (assoc-agent-to-vertices node-cache)
         (assoc-alerts-to-vertices vertex-alerts-map)
         (exm/set-triggered-node-info wf-id wf-nm data/workflow-type-id)
         (exm/set-execution-alerts root-wf-alert-ids)
         (exm/set-start-time (util/now)))}))


(defn- populate-synthetic-wf-data [{:keys[execution-id exec-wf-id wf-id exec-vertex-id job-id job-nm node-type]} node-cache]
    {:execution-id execution-id
     :model
      (-> (exm/new-execution-table)
          (exm/set-start-time (util/now))
          (exm/set-triggered-node-info job-id job-nm data/job-type-id)
          (exm/add-workflows [exec-wf-id])
          (exm/add-workflow-mapping exec-wf-id wf-id)
          (exm/set-root-workflow exec-wf-id)
          (exm/add-vertices [exec-vertex-id])
          (exm/set-vertex-attrs exec-vertex-id job-id job-nm node-type wf-id exec-wf-id)
          (assoc-agent-to-vertices node-cache)
          (exm/finalize))})

(defn resume-workflow-execution-data
  "Fetches an existing execution's data."
  [exec-id node-cache]
  (if (db/synthetic-workflow-execution? exec-id)
    (populate-synthetic-wf-data (db/synthetic-workflow-resumption exec-id) node-cache)
    (let [wf-name (db/get-execution-name exec-id)
          wf-id (db/get-execution-root-wf exec-id)]
      (workflow-execution-data exec-id false wf-id wf-name node-cache))))


(defn setup-execution [wf-id wf-name node-cache]
  (let [exec-id (db/new-execution! wf-id)
        {:keys[model] :as ans} (workflow-execution-data exec-id true wf-id wf-name node-cache)
        vertex-wf-map (exm/vertex-workflow-to-run-map model)]
    (log/debug "The ans is:\n " (with-out-str (clojure.pprint/pprint ans)))
    (log/debug "vertex-wf-map is " vertex-wf-map)
    (db/set-vertex-runs-execution-workflow-mapping vertex-wf-map)
    ans))

(defn setup-synthetic-execution [job-id node-cache]
  (populate-synthetic-wf-data (db/synthetic-workflow-started job-id) node-cache))

  
