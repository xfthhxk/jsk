(ns jsk.console.dashboard
  (:require [taoensso.timbre :as log]
            [jsk.common.util :as util]
            [jsk.common.data :as data]
            [jsk.common.job :as job]
            [jsk.common.workflow :as workflow]
            [jsk.common.db :as db]))


(defn ls-elements
  "Answers with a map of keys :ok, :error.
   Each value is a list of nodes which have a schedule."
  []
  (let [rs (map (fn [{:keys [is-enabled] :as m}]
                  (assoc m :enabled? (= 1 is-enabled)))
                (db/ls-dashboard-elements))
        ok? #(or (nil? %1) (= %1 data/finished-success))
        {:keys [ok error]} (group-by (fn [{:keys [status-id]}]
                                       (if (ok? status-id) :ok :error))
                                     rs)]
        {:ok (sort-by :node-name ok)
         :error (sort-by :node-name error)}))

(defn- update-enabled-status [node-id enabled? user-id]
  (let [{:keys [node-type-id]} (db/get-node-by-id node-id)
        enabled-fn (if (util/job-type? node-type-id) job/update-enabled-status workflow/update-enabled-status)]
    (enabled-fn node-id enabled? user-id)))


(defn enable [node-id user-id]
  (update-enabled-status node-id true user-id))

(defn disable [node-id user-id]
  (update-enabled-status node-id false user-id))
