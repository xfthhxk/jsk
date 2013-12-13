(ns jsk.search
  "Search for executions and other things."
  (:require
            [jsk.db :as db]
            [jsk.ds :as ds]
            [clojure.string :as str]
            [taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal)]))


(defn executions
  "Searches for executions in the range specified."
  [start-ts finish-ts]
  (db/execution-search start-ts finish-ts))
