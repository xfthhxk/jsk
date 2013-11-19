(ns jsk.ps-test
  (:use midje.sweet)
  (:require [jsk.graph :as g]))


(fact "Empty graph is acyclic"
  (g/acyclic? {}) => true)

