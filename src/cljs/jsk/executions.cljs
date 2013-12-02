(ns jsk.executions
  (:require [jsk.rpc :as rpc]
            [jsk.util :as ju]
            [cljs.core.async :as async :refer [<!]]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))


(defn- gen-executing-row-id [vertex-id]
  (str "currently-executing-node-vertex-id-" vertex-id))

;(em/deftemplate make-execution-row :compiled "public/templates/execution.html" [e]
;  "li" (ef/content (str e)))

(em/defsnippet make-execution-row-alt :compiled "public/templates/execution.html" "#execution-list-item" [e]
  "li" (ef/content (str e)))


; make-execution-row and make it the first row
(defn add-execution-alt [e]
  (ju/log "In add-execution arggh")
  (ju/log (str "Got execution msg: " e))
  (ef/at "#exec-list"
         (ef/prepend (make-execution-row-alt e))))

;-----------------------------------------------------------------------
; Make an execution row
;-----------------------------------------------------------------------
(em/defsnippet make-currently-executing-row :compiled "public/templates/execution.html"  "#currently-executing-row"  [e]
  "tr" (ef/do->
          (ef/set-attr :id (gen-executing-row-id (:exec-vertex-id e)))
          (ef/set-attr :data-execution-id (str (:execution-id e)))
          (ef/set-attr :data-vertex-id (str (:exec-vertex-id e))))
  "td.execution-id" (ef/content (str (:execution-id e)))
  "td.vertex-id"    (ef/content (str (:exec-vertex-id e)))
  "td.node-name"    (ef/content (:node-nm e))
  "td.node-start"   (ef/content (str (:start-ts e))))

(em/defsnippet make-execution-successful-row :compiled "public/templates/execution.html"  "#execution-successful-row"  [e]
  "tr" (ef/do->
          (ef/set-attr :data-execution-id (str (:execution-id e)))
          (ef/set-attr :data-vertex-id (str (:exec-vertex-id e))))
  "td.execution-id" (ef/content (str (:execution-id e)))
  "td.vertex-id"    (ef/content (str (:exec-vertex-id e)))
  "td.node-name"    (ef/content (:node-nm e))
  "td.node-start"   (ef/content (str (:start-ts e)))
  "td.node-finish"  (ef/content (str (:finish-ts e))))

(em/defsnippet make-execution-error-row :compiled "public/templates/execution.html"  "#execution-error-row"  [e]
  "tr" (ef/do->
          (ef/set-attr :data-execution-id (str (:execution-id e)))
          (ef/set-attr :data-vertex-id (str (:exec-vertex-id e))))
  "td.execution-id"       (ef/content (str (:execution-id e)))
  "td.vertex-id"          (ef/content (str (:exec-vertex-id e)))
  "td.node-name"          (ef/content (:node-nm e))
  "td.node-start"         (ef/content (str (:start-ts e)))
  "td.node-finish"        (ef/content (str (:finish-ts e)))
  "td.node-error-message" (ef/content (str (:error e))))

(defn- execution-started [e]
  (ef/at "#nodes-currently-executing-tbody" (ef/prepend (make-currently-executing-row e))))

(defn- remove-executing-row
  "Remove the row from the currently executing section."
  [{:keys[exec-vertex-id]}]
  (let [row-selector (str "#" (gen-executing-row-id exec-vertex-id))]
    (ef/at row-selector (ef/remove-node))))


(defmulti execution-finished :success?)

(defmethod execution-finished true [msg]
  (ef/at "#nodes-executed-tbody" (ef/prepend (make-execution-successful-row msg))))

(defmethod execution-finished false [msg]
  (ef/at "#nodes-errored-tbody" (ef/prepend (make-execution-error-row msg))))


(defmulti dispatch :event)

(defmethod dispatch :execution-started [msg])
(defmethod dispatch :execution-finished [msg])

(defmethod dispatch :wf-started [msg])
(defmethod dispatch :wf-finished [msg])

(defmethod dispatch :job-started [msg]
  (ju/log "in job started")
  (ef/at "#nodes-currently-executing-tbody" (ef/prepend (make-currently-executing-row msg))))

(defmethod dispatch :job-finished [msg]
  (ju/log "in job finished")
  (execution-finished msg)
  (remove-executing-row msg))

(defn add-execution [msg]
  (dispatch msg))









