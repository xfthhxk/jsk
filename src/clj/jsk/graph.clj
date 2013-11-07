(ns jsk.graph)



(defprotocol IDigraph
  "Directed graph abstraction."

  (acyclic? [g]
    "Answers if the graph g is acyclic.")

  (add-edge [g v1 v2]
    "Adds a directed edge to g originating from v1 to v2")

  (vertices [g]
    "Seq of all vertices in the graph.")

  (edges [g v]
    "Map of {:in #{} :out #{}}
     Vertices that point to v are in the set pointed to by :in.
     Vertices that v points to are in :out.")

  (inbound [g v]
    "Returns a set of all vertices which point to v.")

  (outbound [g v]
    "Returns a set of all vertices which v points to.")

  (leaves [g]
    "Vertices with no outbound entries.")

  (roots [g]
    "Vertices which have no inbound entries."))


(defn- add-vertex
  "Adds vertex to the graph if it doesn't already exist.
   Returns the update graph."
  [g v]
  (if (g v)
    g
    (assoc g v {:in  #{} :out #{}})))


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

(defn- acyclic-internal? [g]
  (cond
   (empty? g) true
   (empty? (roots g)) false
   :default (acyclic-internal*? g)))


(extend-protocol IDigraph

  clojure.lang.IPersistentMap

  (add-edge [g from to]
    (-> g
        (add-vertex from)
        (add-vertex to)
        (update-in [from :out] conj to)
        (update-in [to   :in]  conj from)))

  (vertices [g]
    (keys g))

  (edges [g v]
    (g v))


  (inbound [g v]
    (get-in g [v :in]))

  (outbound [g v]
    (get-in g [v :out]))


  (leaves [g]
    (filter #(->> %1 (outbound g) empty?) (vertices g)))


  (roots [g]
    (filter #(->> %1 (inbound g) empty?) (vertices g)))


  (acyclic? [g]
    (acyclic-internal? g)))


; acyclic
; dfs from all roots
;



; vertices without any inbound edges
; are going to be the ones that start the workflow

; if a node has no inbound and no outbound edges
; then it should not be part of the workflow
