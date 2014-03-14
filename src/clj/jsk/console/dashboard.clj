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
        status-ok? (fnil #(= %1 data/finished-success) data/finished-success) ;; treat as ok if never run
        group-fn (fn [{:keys [enabled? status-id]}]
                   (cond
                    (true? enabled?) :ok
                    (status-ok? status-id) :ok
                    :default :error))
        {:keys [ok error]} (group-by group-fn rs)]
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
