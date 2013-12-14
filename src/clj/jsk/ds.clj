(ns jsk.ds
  (:require [jsk.graph :as g]))

(def workflow-node-type 2)

(def ^:private initial-vertex-info
  {:node-id 0
   :node-nm ""
   :node-type 0
   :belongs-to-wf 0
   :status 0
   :on-success #{}
   :on-failure #{}})

(defn new-execution-table
  "Answers with a new representation of an empty execution table."
  []
  {:vertices {}
   :graphs {}
   :wf-mappings {:exec-wf-to-wf {}
                 :wf-to-exec-wf {}}
   :root-wf nil})

(defn- deps-kw
  "Answers with :on-success or :on-failure based on success?"
  [success?]
  (if success? :on-success :on-failure))




(defprotocol IExecutionTable
  "Execution table"

  (set-root-workflow [tbl wf]
    "Sets the root workflow.")

  (root-workflow [tbl]
    "Gets the root workflow.")

  (add-workflows [tbl wfs]
    "Adds the wf ids to the tbl.")

  (workflows [tbl]
    "Answer with a seq of all workflows in this tbl.")

  (add-vertices [tbl vertices]
    "Adds vertices to the tbl.")

  (vertices [tbl]
    "Answers with all vertices in this tbl.")

  (workflow-vertices [tbl]
    "Answers with all workflow vertices from tbl.")

  (add-dependency [tbl exec-wf-id vtx next-vtx success?]
    "Adds to vtx's :on-success or :on-failure sets next-vtx
     based on success?")

  (dependencies [tbl vtx success?]
    "Answers with the next set of dependencies")

  (set-vertex-attrs [tbl v node-id node-nm node-type wf-id exec-wf-id]
    "Sets the node-id, name, type, wf-id for vertex v in tbl.")

  (set-vertex-runs-workflow [tbl v exec-wf-id]
     "Sets the execution workflow id that vertex v (workflow node) should run.")

  (vertex-attrs [tbl v]
    "Gets the node id, nm, type and the wf the node belongs to.")

  (add-workflow-mapping [tbl exec-wf-id wf-id]
    "Adds mappings of execution workflow id to workflow id.
     NB. exec-wf-id should be unique and will overwrite an existing one.
     wf-id does not have to be unique.")

  (workflow-graph [tbl wf]
    "Gets the workflow graph for the wf specified.")

  (root-workflow-graph [tbl]
    "Gets the root workflow graph.")

  (workflow-context [tbl vertices]
    "Answers with a map of vertex -> the workflow which owns the vertex.")

  (finalize [tbl]
    "Populates additional data to facilitate processing
     based on information already in tbl.")

  (vertex-workflow-to-run-map [tbl]
    "Call after finalize is called. Returns a map of vertex ids to an execution workflow id
     that vertex is to run."))



    ; for each vertex that's a wf
    ; look at the vertex-attr's node-id
    ; Lookup that node-id in the [:wf-mappings :wf-to-exec-wf node-id] which returns a set
    ; pull out the first item from the set and associate the vertex's :exec-wf-to-run to that
    ; disjoin item from set
    ; update [:wf-mappings :wf-to-exec-wf node-id] to be the new set w/o that one item
    ; mark all vertices which are part of the exec-wf-id's parents to be vertex-id

(defn- assign-parents [tbl exec-wf-id parent-vertex-id]
  (let [vv (-> (workflow-graph tbl exec-wf-id)
               (g/vertices))]
    (reduce (fn[ans v]
              (update-in ans [:vertices v] merge {:parent-vertex parent-vertex-id}))
            tbl
            vv)))

(defn- process-wf-vertex [tbl vertex-id]
  (let [{:keys [node-id] :as attrs} (vertex-attrs tbl vertex-id)
        path [:wf-mappings :wf-to-exec-wf node-id]
        exec-wf-id (first (get-in tbl path))]
    (-> tbl
        (update-in path disj exec-wf-id) ; remove the used wf
        (update-in [:vertices vertex-id] merge {:exec-wf-to-run exec-wf-id})
        (assign-parents exec-wf-id vertex-id))))

(extend-protocol IExecutionTable

  clojure.lang.IPersistentMap

  (set-root-workflow [tbl wf]
    (assoc tbl :root-wf wf))

  (root-workflow [tbl]
    (:root-wf tbl))

  (add-workflows [tbl wfs]
    (reduce (fn[t wf]
              (update-in t [:graphs] assoc wf (g/new-graph)))
            tbl
            wfs))

  (workflows [tbl]
    (keys (get-in tbl [:graphs])))

  (add-vertices [tbl vertices]
    (reduce (fn[t v]
              (update-in t [:vertices] assoc v initial-vertex-info))
            tbl
            vertices))

  (vertices [tbl]
    (keys (get-in tbl [:vertices])))

  (workflow-vertices [tbl]
    (filter (fn[v]
              (-> (vertex-attrs tbl v)
                  :node-type
                  (= workflow-node-type)))
            (vertices tbl)))

  ; add to tbl structure but also to the graph for the exec-wf-id
  (add-dependency [tbl exec-wf-id vtx next-vtx success?]
    (let [v-path [:vertices vtx (deps-kw success?)]
          g-path [:graphs exec-wf-id]]

      (-> tbl
        (update-in v-path conj next-vtx)
        (update-in g-path g/add-edge vtx next-vtx))))

  (dependencies [tbl vtx success?]
    (get-in tbl [:vertices vtx (deps-kw success?)]))

  (set-vertex-attrs [tbl v node-id node-nm node-type wf-id exec-wf-id]
    (update-in tbl [:vertices v]
               merge {:node-id node-id
                      :node-nm node-nm
                      :node-type node-type
                      :belongs-to-wf exec-wf-id}))

  ; used when we are re-running an execution and want to use the assigned values
  ; from the database
  (set-vertex-runs-workflow [tbl v exec-wf-id]
    (update-in tbl [:vertices v] merge {:exec-wf-to-run exec-wf-id}))

  (vertex-attrs [tbl v]
    (get-in tbl [:vertices v]))

  (add-workflow-mapping [tbl exec-wf-id wf-id]
    (let [path [:wf-mappings :wf-to-exec-wf wf-id]
          s (get-in tbl path)
          s' (if s (conj s exec-wf-id) #{exec-wf-id})]

      (-> tbl
          (update-in [:wf-mappings :exec-wf-to-wf] assoc exec-wf-id wf-id)
          (update-in [:wf-mappings :wf-to-exec-wf] assoc wf-id s'))))


  (workflow-graph [tbl wf]
    (get-in tbl [:graphs wf]))

  (root-workflow-graph [tbl]
    (workflow-graph tbl (root-workflow tbl)))

  (workflow-context [tbl vertices]
    (reduce (fn[ans v]
              (assoc ans v (:belongs-to-wf (vertex-attrs tbl v))))
            {}
            vertices))

  (finalize [tbl]
    (reduce (fn[ans v]
              (process-wf-vertex ans v))
            tbl
            (workflow-vertices tbl)))


  (vertex-workflow-to-run-map [tbl]
    (reduce (fn[ans v]
              (assoc ans v (->> v (vertex-attrs tbl) :exec-wf-to-run)))
            {}
            (workflow-vertices tbl))))
