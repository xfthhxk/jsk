(ns jsk.console.dashboard
  (:require [taoensso.timbre :as log]
            [jsk.common.data :as data]
            [jsk.common.db :as db]))


(defn ls-elements
  "Answers with a map of keys :ok, :error.
   Each value is a list of nodes which have a schedule."
  []
  (let [rs (db/ls-dashboard-elements)
        ok? #(or (nil? %1) (= %1 data/finished-success))
        {:keys [ok error]} (group-by (fn [{:keys [status-id]}]
                                       (if (ok? status-id) :ok :error))
                                     rs)]
        {:ok (sort-by :node-name ok)
         :error (sort-by :node-name error)}))
