(ns jsk.dashboard
  (:require [jsk.rfn :as rfn]
            [jsk.util :as util]
            [jsk.workflow :as workflow]
            [jsk.explorer :as explorer]
            [cljs.core.async :as async :refer [<!]]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))


(def dashboard-ok-list "#dashboard-ok-list")
(def dashboard-error-list "#dashboard-error-list")


;; show jobs and workflows with attached schedules
;; two columns OK, Failed
;; move from OK to Failed on failure

;; OK Column
;; WF NAME - link to wf  
;; When run last successfully, link to that execution
;; Freeze Button, Trigger Now button

;; Failed Column
;; WF Name - link to wf
;; When run last execution, link to that execution
;; Unfreeze Button

;; UI needs node-ids and execution-workflow-ids
; rfn/fetch-dashboard-elements
(defn- make-dashboard-node-id [node-id]
  (str "dashboard-node-" node-id))

(defn- trigger-now [node-id node-type-id]
  (condp = node-type-id
    util/job-type-id (rfn/trigger-job-now node-id)
    util/workflow-type-id (rfn/trigger-workflow-now node-id)))

(defn- freeze [node-id node-type-id]
  )

(defn- unfreeze [node-id node-type-id]
  )

(em/defsnippet dashboard-view :compiled "public/templates/dashboard.html" "#dashboard" []
  ;; empty!
  )

(em/defsnippet ok-element :compiled "public/templates/dashboard.html"  "#dashboard-ok-item" [{:keys [node-id node-name node-type-id execution-id status-id start-ts finish-ts]}]
  "li.list-group-item" (ef/set-attr :id (make-dashboard-node-id node-id))
  ".node-name" (ef/do->
                (ef/content node-name)
                (events/listen :click #(explorer/show-node node-id node-type-id)))
  ".last-execution-ts" (if execution-id
                         (ef/do->
                          (ef/content (str (if finish-ts finish-ts start-ts)))
                          (events/listen :click #(workflow/show-execution-visualizer execution-id)))
                         (ef/remove-node))
  ".dashboard-trigger-now" (events/listen :click #(trigger-now node-id node-type-id))
  ".dashboard-freeze" (events/listen :click #(freeze node-id node-type-id)))


(em/defsnippet error-element :compiled "public/templates/dashboard.html"  "#dashboard-error-item" [{:keys [node-id node-name node-type-id execution-id status-id finish-ts]}]
  "li.list-group-item" (ef/set-attr :id (make-dashboard-node-id node-id))
  ".node-name" (ef/do->
                (ef/content node-name)
                (events/listen :click #(explorer/show-node node-id node-type-id)))
  ".last-execution-ts" (if execution-id
                         (ef/do->
                          (ef/content (str finish-ts))
                          (events/listen :click #(workflow/show-execution-visualizer execution-id)))
                         (ef/remove-node))
  ".dashboard-unfreeze" (events/listen :click #(unfreeze node-id node-type-id)))



(defn- append-elements
  "list-id is the div id for the list to append to.
   ee is a seq of maps
   f is the function to apply to each element in ee"
  [list-id ee f]
  (doseq [e ee]
    (ef/at list-id (ef/append (f e)))))

(defn prepend-ok [msg]
  (util/log (str "prepend-ok got called with " (:success? msg)))
  (ef/at dashboard-ok-list (ef/prepend (ok-element msg))))

(defn prepend-error [msg]
  (util/log (str "prepend-error got called with " (:success? msg)))
  (ef/at dashboard-error-list (ef/prepend (error-element msg))))


(defn- show-dashboard []
  (util/showcase (dashboard-view))
  (go
   (let [{:keys [ok error]} (<! (rfn/fetch-dashboard-elements))]
     (append-elements dashboard-ok-list ok ok-element)
     (append-elements dashboard-error-list error error-element))))


;; handle execution finished events coming in
(defmulti dispatch :execution-event)

(defmethod dispatch :execution-started [{:keys [node-id] :as msg}]
  (util/log (str "dashboard execution-started " msg))
  (let [element-id (make-dashboard-node-id node-id)
        element-sel (str "#" element-id)]
    ;; delete existing node
    ;; do nothing if the node-id is not present in the dashboard
    ;; show spinning thing to indicate it is running 
    (when (util/element-exists? element-id)
      (ef/at element-sel (ef/remove-node)) 
      (prepend-ok msg))))

(defmethod dispatch :execution-finished [{:keys [node-id success?] :as msg}]
  (util/log (str "dashboard execution-finished " msg))
  (let [element-id (make-dashboard-node-id node-id)
        element-sel (str "#" element-id)
        prepend-fn (if success? prepend-ok prepend-error)]
    ;; delete existing node
    ;; do nothing if the node-id is not present in the dashboard
    ;; add to the top of ok or error list
    (when (util/element-exists? element-id)
      (ef/at element-sel (ef/remove-node))
      (prepend-fn msg))))

(defmethod dispatch :default [msg]
  )

(defn process-event [msg]
  (dispatch msg))
