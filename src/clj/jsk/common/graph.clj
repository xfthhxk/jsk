(ns jsk.common.graph)

; "Directed graph abstraction."

(defn new-graph
  "Answers with a new graph."
  []
  {})

(defn add-vertex
  "Adds vertex to the graph if it doesn't already exist.
   Returns the updated graph."
  [g v]
  (if (g v)
    g
    (assoc g v {:in  #{} :out #{}})))



(defn add-edge
  "Adds a directed edge to g originating from v1 to v2"
  [g from to]
  (-> g
      (add-vertex from)
      (add-vertex to)
      (update-in [from :out] conj to)
      (update-in [to   :in]  conj from)))

(defn vertices
  "Seq of all vertices in the graph."
  [g]
  (keys g))

(defn edges
  "Map of {:in #{} :out #{}}
   Vertices that point to v are in the set pointed to by :in.
   Vertices that v points to are in :out."
  [g v]
  (g v))

(defn inbound
  "Returns a set of all vertices which point to v."
  [g v]
  (get-in g [v :in]))

(defn outbound
  "Returns a set of all vertices which v points to."
  [g v]
  (get-in g [v :out]))

(defn leaves
  "Vertices with no outbound entries."
  [g]
  (filter #(->> %1 (outbound g) empty?) (vertices g)))


(defn roots
  "Vertices which have no inbound entries."
  [g]
  (into #{} (filter #(->> %1 (inbound g) empty?) (vertices g))))

(defn- acyclic-internal*? [g]
  (loop [unexplored (-> g roots) visited #{} path () ]
    (if (empty? unexplored)
      true
      (let [v (first unexplored)
            visited' (conj visited v)
            path' (conj path v)
            [w & vv :as outs] (seq (outbound g v))
            unexplored' (concat outs (rest unexplored))]
        (cond
          (some #{w} path') false
          (nil? w) true
          (not (visited' w)) (recur unexplored' visited' path'))))))

(defn- acyclic-internal?
  [g]
  (cond
   (empty? g) true
   (empty? (roots g)) false
   :default (acyclic-internal*? g)))

(defn acyclic? [g]
  "Answers if the graph g is acyclic."
  (acyclic-internal? g))
