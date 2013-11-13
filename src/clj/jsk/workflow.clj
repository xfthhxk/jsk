(ns jsk.workflow
  (:require [taoensso.timbre :as timbre :refer (info warn error)]
            [bouncer [core :as b] [validators :as v]]
            [clojure.stacktrace :as st]
            [jsk.quartz :as q]
            [jsk.schedule :as s]
            [jsk.util :as ju]
            [jsk.db :as db]
            [clojure.core.async :refer [put!]])
  (:use [korma core db]
        [swiss-arrows core]))

;-----------------------------------------------------------------------
; Workflows lookups
;-----------------------------------------------------------------------
(defn ls-workflows
  "Lists all workflows"
  []
  (db/ls-workflows))

(defn enabled-workflows
  "Gets all active workflows."
  []
  (db/enabled-workflows))

(defn get-workflow
  "Gets a workflow for the id specified"
  [id]
  (db/get-workflow id))

(defn get-workflow-name
  "Answers with the workflow name for the workflow id, otherwise nil if no such workflow."
  [id]
  (db/get-workflow-name id))

(defn get-workflow-by-name
  "Gets a workflow by name if one exists otherwise returns nil"
  [nm]
  (db/get-workflow-by-name nm))

(defn workflow-name-exists?
  "Answers true if workflow name exists"
  [nm]
  (db/workflow-name-exists? nm))

(defn unique-name? [id wname]
  (if-let [j (get-workflow-by-name wname)]
    (= id (:workflow-id j))
    true))


(defn workflow-graph
  "Answers with an IDigraph for the workflow id specified."
  [id]
  (db/get-workflow-graph id))



(defn validate-save [{:keys [workflow-id] :as w}]
  (-> w
      (b/validate
       :workflow-name [v/required [(partial unique-name? workflow-id) :message "Workflow name must be unique."]])
      first))

;-----------------------------------------------------------------------
; Saving the graph
;-----------------------------------------------------------------------
(defn- key-layout-by-id [layout]
  (reduce (fn [ans m]
            (assoc ans (:job-id m) (:css-text m)))
          {}
          layout))

; saves vertices to db and return a map of node-id to vertex-id
(defn- save-vertices [workflow-id layout connections]
  (let [vv (reduce (fn[ans c]
                     (-> ans (conj (:src-node-id c))
                             (conj (:tgt-node-id c)))) #{} connections)
        layout-map (key-layout-by-id layout)]
    (reduce (fn[m v]
              (assoc m v (db/save-workflow-vertex workflow-id v (layout-map v))))
            {}
            vv)))

(defn- save-graph [{:keys [workflow-id connections]} layout]
  (let [job->vertex (save-vertices workflow-id layout connections)]
    (doseq [{:keys [success? src-node-id tgt-node-id]} connections]
      (db/save-workflow-edge (job->vertex src-node-id)
                             (job->vertex tgt-node-id)
                             success?))))

(defn- save-workflow* [{:keys[workflow-id connections] :as w} layout user-id]
  (transaction
    (db/rm-workflow-graph workflow-id) ; rm existing and add new
    (let [workflow-id (db/save-workflow w user-id)]
      (save-graph w layout)
      {:success? true :workflow-id workflow-id})))

(defn save-workflow!
  "Saves the workflow to the database and the scheduler."
  [{:keys [layout workflow]} user-id]
  (if-let [errors (validate-save workflow)]
    (ju/make-error-response errors)
    (save-workflow* workflow layout user-id)))

