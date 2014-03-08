(ns jsk.node
  (:require [jsk.rpc :as rpc]
            [jsk.util :as util]
            [jsk.rfn :as rfn]
            [cljs.core.async :as async :refer [<!]]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:use [jayq.core :only [$]])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))

(defn- current-node-id [dom-id]
  (when-let [e (util/element-by-id dom-id)]
    (-> e $ (.data "node-id") util/str->int)))

;;----------------------------------------------------------------------
;; Schedule Assoc
;;----------------------------------------------------------------------
(def node-schedules-tab-sel "#node-schedules")
(def node-schedules-list-sel "#node-schedules-list")


(em/defsnippet one-node-schedule-assoc :compiled "public/templates/node-common.html" "#single-node-schedule-assoc-item" [node-schedule-id schedule-name]
  "li" (ef/set-attr :id (str "node-schedule-" node-schedule-id))
  "label" (ef/content schedule-name)
  "button.close" (events/listen :click #(rfn/rm-schedule-assoc node-schedule-id)))

(em/defsnippet list-node-schedule-assocs :compiled "public/templates/node-common.html" "#node-schedules-list" [node-id schedule-assocs]
  "#node-schedules-list" (ef/set-attr :data-node-id (str node-id))
  "#node-schedules-list > :not(li:first-child)" (ef/remove-node)
  "#node-schedules-list > li" (em/clone-for [{:keys [schedule-name node-schedule-id]} schedule-assocs]
                                  "label" #(ef/at (util/parent-node %1)
                                                  (ef/set-attr :id (str "node-schedule-" node-schedule-id)))
                                  "label" (ef/content schedule-name)
                                  "button.close" (events/listen :click #(rfn/rm-schedule-assoc node-schedule-id))))

(defn populate-schedule-assoc-list [node-id schedule-assocs]
  (println (str "populate-schedule-assoc-list for " node-id " with " schedule-assocs))
  (ef/at node-schedules-tab-sel (ef/content (list-node-schedule-assocs node-id schedule-assocs))))

(defn append-schedule-assoc [node-id node-schedule-id schedule-name]
  (when-let [current-node-id (current-node-id "node-schedules-list")]
    (println (str "current-node id is " current-node-id ", and node-id is " node-id))
    (when (= node-id current-node-id)
      (println "adding new node-schedule assoc to current ui")
      (ef/at node-schedules-list-sel (ef/append (one-node-schedule-assoc node-schedule-id schedule-name))))))


(defn save-schedule-assoc
  "Adds the schedule assoc to the currently displayed job.  The hidden job-id element."
  [schedule-id]
  (let [node-id (current-node-id "node-schedules-list")]
    (rfn/save-schedule-assoc node-id schedule-id)))

;;----------------------------------------------------------------------
;; Alert Assoc
;;----------------------------------------------------------------------
(def node-alerts-tab-sel "#node-alerts")
(def node-alerts-list-sel "#node-alerts-list")

(em/defsnippet one-node-alert-assoc :compiled "public/templates/node-common.html" "#single-node-alert-assoc-item" [node-alert-id alert-name]
  "li" (ef/set-attr :id (str "node-alert-" node-alert-id))
  "label" (ef/content alert-name)
  "button.close" (events/listen :click #(rfn/rm-alert-assoc node-alert-id)))

(em/defsnippet list-node-alert-assocs :compiled "public/templates/node-common.html" "#node-alerts-list" [node-id alert-assocs]
  "#node-alerts-list" (ef/set-attr :data-node-id (str node-id))
  "#node-alerts-list > :not(li:first-child)" (ef/remove-node)
  "#node-alerts-list > li" (em/clone-for [{:keys [alert-name node-alert-id]} alert-assocs]
                                  "label" #(ef/at (util/parent-node %1)
                                                  (ef/set-attr :id (str "node-alert-" node-alert-id)))
                                  "label" (ef/content alert-name)
                                  "button.close" (events/listen :click #(rfn/rm-alert-assoc node-alert-id))))

(defn populate-alert-assoc-list [node-id alert-assocs]
  (println "populate-alert-assoc-list for " node-id " with " alert-assocs)
  (ef/at node-alerts-tab-sel (ef/content (list-node-alert-assocs node-id alert-assocs))))

(defn append-alert-assoc [node-id node-alert-id alert-name]
  (when-let [e (util/element-by-id "node-alerts-list")]
    (println "node-alerts-list is in the dom")
    (let [current-node-id (-> e $ (.data "node-id") util/str->int)]
      (println "current-node id is " current-node-id ", and node-id is " node-id)

      (when (= node-id current-node-id)
        (println "adding new node-alert assoc to current ui")
        (ef/at node-alerts-list-sel (ef/append (one-node-alert-assoc node-alert-id alert-name)))))))


(defn save-alert-assoc
  "Adds the alert assoc to the currently displayed job.  The hidden job-id element."
  [alert-id]
  (let [node-id (current-node-id "node-alerts-list")]
    (rfn/save-alert-assoc node-id alert-id)))
