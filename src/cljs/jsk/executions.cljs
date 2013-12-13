(ns jsk.executions
  (:require [jsk.rpc :as rpc]
            [jsk.util :as ju]
            [jsk.workflow :as w]
            [cljs.core.async :as async :refer [<!]]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))


(defn- gen-executing-row-id [vertex-id]
  (str "currently-executing-execution-id-" vertex-id))

;-----------------------------------------------------------------------
; Executions
;-----------------------------------------------------------------------
(em/defsnippet make-execution-in-progress-row :compiled "public/templates/execution.html"  "#execution-in-progress-row"  [e]
  "tr" (ef/do->
          (events/listen :click (fn[event] (w/show-execution-visualizer (:execution-id e))))
          (ef/set-attr :id (gen-executing-row-id (:execution-id e))) ; needs a special id to be able to update later
          (ef/set-attr :data-execution-id (str (:execution-id e))))
  "td.execution-id" (ef/content (str (:execution-id e)))
  "td.execution-name"    (ef/content (:wf-name e))
  "td.execution-start"   (ef/content (str (:start-ts e))))

(em/defsnippet make-execution-successful-row :compiled "public/templates/execution.html"  "#execution-successful-row"  [e]
  "tr" (ef/do->
          (events/listen :click (fn[event] (w/show-execution-visualizer (:execution-id e))))
          (ef/set-attr :data-execution-id (str (:execution-id e))))
  "td.execution-id" (ef/content (str (:execution-id e)))
  "td.execution-name"    (ef/content (:wf-name e))
  "td.execution-start"   (ef/content (str (:start-ts e)))
  "td.execution-finish"  (ef/content (str (:finish-ts e))))

(em/defsnippet make-execution-error-row :compiled "public/templates/execution.html"  "#execution-error-row"  [e]
  "tr" (ef/do->
          (events/listen :click (fn[event] (w/show-execution-visualizer (:execution-id e))))
          (ef/set-attr :data-execution-id (str (:execution-id e))))
  "td.execution-id"       (ef/content (str (:execution-id e)))
  "td.execution-name"          (ef/content (:wf-name e))
  "td.execution-start"         (ef/content (str (:start-ts e)))
  "td.execution-finish"        (ef/content (str (:finish-ts e))))

;-----------------------------------------------------------------------
; Nodes
;-----------------------------------------------------------------------
(em/defsnippet make-node-executing-row :compiled "public/templates/execution.html"  "#node-executing-row"  [e]
  "tr" (ef/do->
          (events/listen :click (fn[event] (w/show-execution-visualizer (:execution-id e))))
          (ef/set-attr :id (gen-executing-row-id (:exec-vertex-id e))) ; needs a special id to be able to update later
          (ef/set-attr :data-execution-id (str (:execution-id e)))
          (ef/set-attr :data-vertex-id (str (:exec-vertex-id e))))
  "td.execution-id" (ef/content (str (:execution-id e)))
  "td.vertex-id"    (ef/content (str (:exec-vertex-id e)))
  "td.node-name"    (ef/content (:node-nm e))
  "td.node-start"   (ef/content (str (:start-ts e))))

(em/defsnippet make-node-successful-row :compiled "public/templates/execution.html"  "#node-successful-row"  [e]
  "tr" (ef/do->
          (events/listen :click (fn[event] (w/show-execution-visualizer (:execution-id e))))
          (ef/set-attr :data-execution-id (str (:execution-id e)))
          (ef/set-attr :data-vertex-id (str (:exec-vertex-id e))))
  "td.execution-id" (ef/content (str (:execution-id e)))
  "td.vertex-id"    (ef/content (str (:exec-vertex-id e)))
  "td.node-name"    (ef/content (:node-nm e))
  "td.node-start"   (ef/content (str (:start-ts e)))
  "td.node-finish"  (ef/content (str (:finish-ts e))))

(em/defsnippet make-node-error-row :compiled "public/templates/execution.html"  "#node-error-row"  [e]
  "tr" (ef/do->
          (events/listen :click (fn[event] (w/show-execution-visualizer (:execution-id e))))
          (ef/set-attr :data-execution-id (str (:execution-id e)))
          (ef/set-attr :data-vertex-id (str (:exec-vertex-id e))))
  "td.execution-id"       (ef/content (str (:execution-id e)))
  "td.vertex-id"          (ef/content (str (:exec-vertex-id e)))
  "td.node-name"          (ef/content (:node-nm e))
  "td.node-start"         (ef/content (str (:start-ts e)))
  "td.node-finish"        (ef/content (str (:finish-ts e)))
  "td.node-error-message" (ef/content (str (:error e))))

(defn- execution-started [e]
  (ef/at "#nodes-currently-executing-tbody" (ef/prepend (make-node-executing-row e))))

(defn- remove-executing-row
  "Remove the row from the currently executing section."
  [{:keys[execution-id]}]
  (let [row-selector (str "#" (gen-executing-row-id execution-id))]
    (ef/at row-selector (ef/remove-node))))


(defmulti execution-finished :success?)

(defmethod execution-finished true [msg]
  (ef/at "#successful-executions-tbody" (ef/prepend (make-execution-successful-row msg))))

(defmethod execution-finished false [msg]
  (ef/at "#errored-executions-tbody" (ef/prepend (make-execution-error-row msg))))


(defmulti dispatch :event)

(defmethod dispatch :execution-started [msg]
  (ef/at "#current-executions-tbody" (ef/prepend (make-execution-in-progress-row msg))))

(defmethod dispatch :execution-finished [msg]
  (execution-finished msg)
  (remove-executing-row msg))

(defmethod dispatch :wf-started [msg])
(defmethod dispatch :wf-finished [msg])

(defmethod dispatch :job-started [msg])

(defmethod dispatch :job-finished [msg])

(defn add-execution [msg]
  (dispatch msg))









