(ns jsk.executions
  (:require [jsk.rfn :as rfn]
            [jsk.util :as util]
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
  "td.execution-name"    (ef/content (:node-name e))
  "td.execution-start"   (ef/content (util/format-ts (:start-ts e))))

;  "a.execution-view-action" (events/listen :click (fn[event] (w/show-execution-visualizer (:execution-id e))))
;  "a.execution-abort-action" (events/listen :click (fn[event] (rfn/abort-execution (:execution-id e)))))


(em/defsnippet make-execution-successful-row :compiled "public/templates/execution.html"  "#execution-successful-row"  [e]
  "tr" (ef/do->
          (events/listen :click (fn[event] (w/show-execution-visualizer (:execution-id e))))
          (ef/set-attr :data-execution-id (str (:execution-id e))))
  "td.execution-id" (ef/content (str (:execution-id e)))
  "td.execution-name"    (ef/content (:node-name e))
  "td.execution-start"   (ef/content (util/format-ts (:start-ts e)))
  "td.execution-finish"  (ef/content (util/format-ts (:finish-ts e))))

(em/defsnippet make-execution-unsuccessful-row :compiled "public/templates/execution.html"  "#execution-unsuccessful-row"  [e]
  "tr" (ef/do->
          (events/listen :click (fn[event] (w/show-execution-visualizer (:execution-id e))))
          (ef/set-attr :data-execution-id (str (:execution-id e))))
  "td.execution-id"       (ef/content (str (:execution-id e)))
  "td.execution-name"     (ef/content (:node-name e))
  "td.execution-status"   (ef/content (util/status-id->desc (:status-id e)))
  "td.execution-start"    (ef/content (util/format-ts (:start-ts e)))
  "td.execution-finish"   (ef/content (util/format-ts (:finish-ts e))))

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
  "td.node-start"   (ef/content (util/format-ts (:start-ts e))))

(em/defsnippet make-node-successful-row :compiled "public/templates/execution.html"  "#node-successful-row"  [e]
  "tr" (ef/do->
          (events/listen :click (fn[event] (w/show-execution-visualizer (:execution-id e))))
          (ef/set-attr :data-execution-id (str (:execution-id e)))
          (ef/set-attr :data-vertex-id (str (:exec-vertex-id e))))
  "td.execution-id" (ef/content (str (:execution-id e)))
  "td.vertex-id"    (ef/content (str (:exec-vertex-id e)))
  "td.node-name"    (ef/content (:node-nm e))
  "td.node-start"   (ef/content (util/format-ts (:start-ts e)))
  "td.node-finish"  (ef/content (util/format-ts (:finish-ts e))))

(em/defsnippet make-node-unsuccessful-row :compiled "public/templates/execution.html"  "#node-unsuccessful-row"  [e]
  "tr" (ef/do->
          (events/listen :click (fn[event] (w/show-execution-visualizer (:execution-id e))))
          (ef/set-attr :data-execution-id (str (:execution-id e)))
          (ef/set-attr :data-vertex-id (str (:exec-vertex-id e))))
  "td.execution-id"       (ef/content (str (:execution-id e)))
  "td.vertex-id"          (ef/content (str (:exec-vertex-id e)))
  "td.node-name"          (ef/content (:node-nm e))
  "td.node-start"         (ef/content (util/format-ts (:start-ts e)))
  "td.node-finish"        (ef/content (util/format-ts (:finish-ts e)))
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
  (ef/at "#unsuccessful-executions-tbody" (ef/prepend (make-execution-unsuccessful-row msg))))


(defmulti dispatch :execution-event)

(defmethod dispatch :execution-started [msg]
  (ef/at "#current-executions-tbody" (ef/prepend (make-execution-in-progress-row msg))))

(defmethod dispatch :execution-finished [msg]
  (execution-finished msg)
  (remove-executing-row msg))

(defmethod dispatch :default [msg]
  ;; empty! since workflow level (job execution events are also
  ;; dropped in here.  And here we don't care about those.
  )


(defn add-execution [msg]
  (dispatch msg))
