(ns jsk.common.graph-test
  (:use midje.sweet)
  (:require [jsk.common.graph :as g]))

(defn make-graph-1 []
  )


(fact "Empty graph is acyclic"
  (g/acyclic? (g/new-graph)) => true)

(fact "A graph without 'roots' is *not* acyclic.")

