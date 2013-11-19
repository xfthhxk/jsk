(ns jsk.conductor-test
  (:use midje.sweet)
  (:require [jsk.conductor :as c]
            [jsk.graph :as g]
            [jsk.db :as db]))

(defn- build-graph []
  (-> {}
      (g/add-edge 1 2)
      (g/add-edge 1 3)
      (g/add-edge 3 4)
      (g/add-edge 3 5)))


(defn- build-tbl []
  {1 {:status db/finished-success  :on-success #{3} :on-fail #{2}}
   2 {:status db/unexecuted-status :on-success #{}  :on-fail #{}}
   3 {:status db/finished-error    :on-success #{5} :on-fail #{4}}
   4 {:status db/finished-success  :on-success #{}  :on-fail #{}}
   5 {:status db/unexecuted-status :on-success #{}  :on-fail #{}}})


(defn- process-root [r wfg tbl]

  )


(fact "Test workflow finished"
  (let [wfg (build-graph)
        tbl (build-tbl)
        roots (g/roots wfg)]
    ))

