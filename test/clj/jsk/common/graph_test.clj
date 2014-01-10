(ns jsk.common.graph-test
  (:use midje.sweet)
  (:require [jsk.common.graph :as g]))


(fact "Empty graph is acyclic"
  (g/acyclic? {}) => true)

