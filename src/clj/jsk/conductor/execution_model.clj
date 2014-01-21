(ns jsk.conductor.execution-model
  (:require [jsk.common.graph :as graph]
            [jsk.common.data :as data]
            [jsk.common.util :as util]
            [clojure.set :as set]
            [taoensso.timbre :as log]))

(def ^:private initial-vertex-info
  {:node-id 0
   :node-nm ""
   :node-type 0
   :belongs-to-wf 0
   :status 0
   :agent-name nil
   :on-success #{}
   :on-failure #{}})

(defn new-execution-table
  "Answers with a new representation of an empty execution table."
  []
  {:execution-name nil
   :start-time nil
   :vertices {}
   :graphs {}
   :failed-exec-wfs #{}
   :wf-mappings {:exec-wf-to-wf {}
                 :wf-to-exec-wf {}}
   :root-wf nil})

(defn- deps-kw
  "Answers with :on-success or :on-failure based on success?"
  [success?]
  (if success? :on-success :on-failure))

(defn set-execution-name
  "Sets the execution name for model"
  [model nm]
  (assoc model :execution-name nm))

(defn execution-name
  "Answers with the name for this execution"
  [model]
  (:execution-name model))

(defn set-start-time
  "Sets the execution's start."
  [model time]
  (assoc model :start-time time))

(defn start-time
  "Gets the execution's start."
  [model]
  (:start-time model))


(defn set-root-workflow
  "Sets the root workflow."
  [model wf]
  (assoc model :root-wf wf))

(defn root-workflow
  "Gets the root workflow."
  [model]
  (:root-wf model))

(defn add-workflows
  "Adds the wf ids to the model"
  [model wfs]
  (reduce (fn[t wf]
            (update-in t [:graphs] assoc wf (graph/new-graph)))
          model
          wfs))

(defn workflows
  "Answers with a seq of all workflows in the model."
  [model]
  (keys (get-in model [:graphs])))

(defn add-vertices
  "Adds vertices to the model."
  [model vertices]
  (reduce (fn[t v]
            (update-in t [:vertices] assoc v initial-vertex-info))
          model
          vertices))

(defn vertices
  "Answers with all vertex-ids in this model."
  [model]
  (set (keys (get-in model [:vertices]))))


; add to model structure but also to the graph for the exec-wf-id
(defn add-dependency
  "Adds next-vtx to vtx's :on-success or :on-failure set based on success?"
  [model exec-wf-id vtx next-vtx success?]
  (let [v-path [:vertices vtx (deps-kw success?)]
        g-path [:graphs exec-wf-id]]

    (-> model
      (update-in v-path conj next-vtx)
      (update-in g-path graph/add-edge vtx next-vtx))))

(defn dependencies
  "Answers with the next set of dependencies."
  [model vtx success?]
  (get-in model [:vertices vtx (deps-kw success?)]))

(defn set-vertex-attrs
  "Sets various attributes for vertex v in the model."
  [model v node-id node-nm node-type wf-id exec-wf-id]
  (update-in model [:vertices v]
             merge {:node-id node-id
                    :node-nm node-nm
                    :node-type node-type
                    :belongs-to-wf exec-wf-id}))

; used when we are re-running an execution and want to use the assigned values
; from the database
(defn set-vertex-runs-workflow
  "Sets the execution workflow id that vertex v (a workflow node) should run."
  [model v exec-wf-id]
  (update-in model [:vertices v] merge {:exec-wf-to-run exec-wf-id}))

(defn vertex-attrs
  "Gets various attributes for the vertex v"
  [model v]
  (get-in model [:vertices v]))


(defn assoc-agent-name
  "Associates the agent-name to the vertex represented by v-id.
   agent-name is a non-nil, non-empty string."
  [model v-id agent-name]
  (assert (seq agent-name) "nil agent-name")
  (assoc-in model [:vertices v-id :agent-name] agent-name))


; s (conj (get-in model path) exec-wf
;s' (if s (conj s exec-wf-id) #{exec-wf-id})]

(defn add-workflow-mapping
  "Adds mappings of execution workflow id to workflow id.
   NB. exec-wf-id should be unique and will overwrite an existing one.
   wf-id does not have to be unique."
  [model exec-wf-id wf-id]
  (let [path [:wf-mappings :wf-to-exec-wf wf-id]
        s (-> model (get-in path) set (conj exec-wf-id))]

    (-> model
        (update-in [:wf-mappings :exec-wf-to-wf] assoc exec-wf-id wf-id)
        (update-in [:wf-mappings :wf-to-exec-wf] assoc wf-id s))))


(defn workflow-graph
  "Gets the workflow graph for the wf specified."
  [model wf]
  (get-in model [:graphs wf]))

(defn root-workflow-graph
  "Gets the root workflow graph."
  [model]
  (workflow-graph model (root-workflow model)))

(defn workflow-context
  "Answers with a map of vertex -> the workflow which owns the vertex."
  [model vertices]
  (reduce (fn[ans v]
            (assoc ans v (:belongs-to-wf (vertex-attrs model v))))
          {}
          vertices))


(defn workflow-vertices
  "Andwers with all workflow vertices within the model."
  [model]
  (filter (fn[v]
            (-> (vertex-attrs model v)
                :node-type
                util/workflow-type?))
          (vertices model)))

(defn job-vertices
  "Andwers with all job vertices within the model."
  [model]
  (set/difference (vertices model) (workflow-vertices model)))

(defn vertex-workflow-to-run-map
  "Call after finalize is called. Returns a map of vertex ids to an execution workflow id
   that vertex is to run."
  [model]
  (let [f (fn[ans v]
            (assoc ans v (->> v (vertex-attrs model) :exec-wf-to-run)))]
       (reduce f {} (workflow-vertices model))))


(defn update-status [model status & vertex-ids]
  "Sets the status for vertex-ids"
  (reduce (fn[ans v]
            (assoc-in ans [:vertices v] status))
          model
          (flatten vertex-ids)))

(defn filter-vertices
  [model pred]
  (->> model
       vertices
       (filter pred)))

(defn status-count
  "Count of vertices which have the specified status."
  [model status-id exec-wf-id]
  (-> model
      (filter-vertices (fn [{:keys [status belongs-to-wf]}]
                         (and (= status status-id)
                              (= belongs-to-wf exec-wf-id))))
      count))

(defn parent-vertex
  "vertex-id's parent vertex id or nil when vertex-id is the root."
  [model vertex-id]
  (:parent-vertex (vertex-attrs model vertex-id)))

    ; for each vertex that's a wf
    ; look at the vertex-attr's node-id
    ; Lookup that node-id in the [:wf-mappings :wf-to-exec-wf node-id] which returns a set
    ; pull out the first item from the set and associate the vertex's :exec-wf-to-run to that
    ; disjoin item from set
    ; update [:wf-mappings :wf-to-exec-wf node-id] to be the new set w/o that one item
    ; mark all vertices which are part of the exec-wf-id's parents to be vertex-id

(defn- assign-parents [model exec-wf-id parent-vertex-id]
  (let [vv (-> (workflow-graph model exec-wf-id)
               (graph/vertices))]
    (reduce (fn[ans v]
              (update-in ans [:vertices v] merge {:parent-vertex parent-vertex-id}))
            model
            vv)))


(defn- process-wf-vertex [model vertex-id]
  (let [{:keys [node-id] :as attrs} (vertex-attrs model vertex-id)
        path [:wf-mappings :wf-to-exec-wf node-id]
        exec-wf-id (first (get-in model path))]
    (-> model
        (update-in path disj exec-wf-id) ; remove the used wf
        (update-in [:vertices vertex-id] merge {:exec-wf-to-run exec-wf-id})
        (assign-parents exec-wf-id vertex-id))))

(defn finalize
  "Populates additional data to facilitate processing
   based on information already in model."
  [model]
  (reduce (fn[ans v]
            (process-wf-vertex ans v))
          model
          (workflow-vertices model)))

(defn partition-by-node-type
  "Partitions the node-ids by node-type and returns a two element vector.
   The first element is a set of job vertices, the second a set of workflow vertices."
  [model vertex-ids]
  (let [f (fn [id] (->> id (vertex-attrs model) :node-type))
        type-map (group-by f vertex-ids)]
    (log/debug "vertex-ids: " vertex-ids ", type-map: " type-map)
    [(-> data/job-type-id type-map set)
     (-> data/workflow-type-id type-map set)]))


(defn single-workflow-context-for-vertices
  "Ensures the vertex-ids belong to the same wf-id and returns that wf-id."
  [model vertex-ids]
  (let [wf-ids (-> model (workflow-context vertex-ids) vals distinct doall)]
    (assert (= 1 (count wf-ids))
          (str "Not all vertices are in the same workflow: " vertex-ids ", wf-ids: " wf-ids))
    (first wf-ids)))

(defn mark-exec-wf-failed
  "Marks the execution workflow specified by exec-wf-id as failed."
  [model exec-wf-id]
  (assert exec-wf-id "nil exec-wf-id")
  (assert (:failed-exec-wfs model) "nil failed-exec-wfs")
  (update-in model [:failed-exec-wfs] conj exec-wf-id))

(defn failed-exec-wf?
  "Answers if the exec-wf-id has failed."
  [model exec-wf-id]
  (assert exec-wf-id "nil exec-wf-id")
  (-> model (get-in [:failed-exec-wfs]) (contains? exec-wf-id)))
