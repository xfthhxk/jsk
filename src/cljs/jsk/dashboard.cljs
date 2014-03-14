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

(def ^:private executing-nodes (atom #{}))

(defn- executing? [node-id]
  (contains? @executing-nodes node-id))

(defn- mark-as-executing [node-id]
  (swap! executing-nodes conj node-id))

(defn- mark-execution-finished [node-id]
  (swap! executing-nodes disj node-id))


(def ^:private scheduled-disabled-nodes (atom #{}))

(defn- populate-scheduled-disabled-nodes [nn]
  (let [disabled (->> nn
                      (filter #(-> %1 :enabled? not))
                      (map :node-id))]
    (swap! scheduled-disabled-nodes into disabled)))

(defn- disabled-scheduled-node? [node-id]
  (contains? @scheduled-disabled-nodes node-id))

(def dashboard-ok-list "#dashboard-ok-list")
(def dashboard-error-list "#dashboard-error-list")


;; show jobs and workflows with attached schedules
;; two columns OK, Failed
;; move from OK to Failed on failure

;; OK Column
;; WF NAME - link to wf  
;; When run last successfully, link to that execution
;; Enable Button, Trigger Now button

;; Failed Column
;; WF Name - link to wf
;; When run last execution, link to that execution
;; Disable Button


;; UI needs node-ids and execution-workflow-ids
; rfn/fetch-dashboard-elements
(defn- make-dashboard-node-id [node-id]
  (str "dashboard-node-" node-id))

(defn- make-dashboard-node-id-executing [node-id]
  (str "dashboard-node-executing-" node-id))

(defn- trigger-now [node-id node-type-id]
  (condp = node-type-id
    util/job-type-id (rfn/trigger-job-now node-id)
    util/workflow-type-id (rfn/trigger-workflow-now node-id)))

(em/defsnippet dashboard-view :compiled "public/templates/dashboard.html" "#dashboard" []
  ;; empty!
  )



(em/defsnippet ok-element :compiled "public/templates/dashboard.html"  "#dashboard-ok-item" [{:keys [node-id node-name node-type-id enabled? execution-id status-id start-ts finish-ts]}]
  "li.list-group-item" (ef/set-attr :id (make-dashboard-node-id node-id))
  "span.glyphicon" (if (disabled-scheduled-node? node-id)
                     (ef/content "")
                     (ef/remove-node))
  ".node-name" (ef/do->
                (ef/content node-name)
                (events/listen :click #(explorer/show-node node-id node-type-id)))
  ".last-execution-ts" (if execution-id
                         (ef/do->
                          (ef/content (util/format-ts (if finish-ts finish-ts start-ts)))
                          (events/listen :click #(workflow/show-execution-visualizer execution-id)))
                         (ef/remove-node))
  "img.executing" (if (or (executing? node-id)
                          (and start-ts (not finish-ts)))
                    (ef/set-attr :id (make-dashboard-node-id-executing node-id))
                    (ef/remove-node))
  ".dashboard-trigger-now" (if (disabled-scheduled-node? node-id)
                             (ef/remove-node)
                             (events/listen :click #(trigger-now node-id node-type-id)))
  ".dashboard-disable" (if (disabled-scheduled-node? node-id)
                         (ef/remove-node)
                        (events/listen :click #(rfn/disable-node node-id)))
  ".dashboard-enable" (if (disabled-scheduled-node? node-id)
                        (events/listen :click #(rfn/enable-node node-id))
                        (ef/remove-node)))



(em/defsnippet error-element :compiled "public/templates/dashboard.html"  "#dashboard-error-item" [{:keys [node-id node-name node-type-id enabled? execution-id status-id finish-ts]}]
  "li.list-group-item" (ef/set-attr :id (make-dashboard-node-id node-id))
  ".node-name" (ef/do->
                (ef/content node-name)
                (events/listen :click #(explorer/show-node node-id node-type-id)))
  ".last-execution-ts" (if execution-id
                         (ef/do->
                          (ef/content (util/format-ts finish-ts))
                          (events/listen :click #(workflow/show-execution-visualizer execution-id)))
                         (ef/remove-node))
  ".dashboard-enable" (events/listen :click #(rfn/enable-node node-id)))



(defn- append-elements
  "list-id is the div id for the list to append to.
   ee is a seq of maps
   f is the function to apply to each element in ee"
  [list-id ee f]
  (doseq [e ee]
    (ef/at list-id (ef/append (f e)))))

(defn prepend-ok [msg]
  (ef/at dashboard-ok-list (ef/prepend (ok-element msg))))

(defn prepend-error [msg]
  (ef/at dashboard-error-list (ef/prepend (error-element msg))))

(defn append-ok [msg]
  (ef/at dashboard-ok-list (ef/append (ok-element msg))))

(defn append-error [msg]
  (ef/at dashboard-error-list (ef/append (error-element msg))))

(defn- show-dashboard []
  (util/showcase (dashboard-view))
  (go
   (let [{:keys [ok error]} (<! (rfn/fetch-dashboard-elements))]
     (populate-scheduled-disabled-nodes ok)
     (println "The scheduled disableds are " @scheduled-disabled-nodes)
     (append-elements dashboard-ok-list ok ok-element)
     (append-elements dashboard-error-list error error-element))))


;; handle execution finished events coming in
(defmulti dispatch :execution-event)

(defmethod dispatch :execution-started [{:keys [node-id] :as msg}]
  (let [element-id (make-dashboard-node-id node-id)
        element-sel (str "#" element-id)]
    ;; delete existing node
    ;; do nothing if the node-id is not present in the dashboard
    ;; show spinning thing to indicate it is running 
    (when (util/element-exists? element-id)
      (ef/at element-sel (ef/remove-node)) 
      (mark-as-executing node-id)
      (prepend-ok msg))))

(defmethod dispatch :execution-finished [{:keys [node-id success?] :as msg}]
  (mark-execution-finished node-id)
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

(defmulti dispatch-crud :crud-event)

(defmethod dispatch-crud :schedule-assoc-add [{:keys [node-id] :as msg}]
  (let [dom-node-id (make-dashboard-node-id node-id)]
    ;; if was last assoc and node is present, then remove it from the dashboard
    (when (-> dom-node-id util/element-exists? not)
      (append-elements dashboard-ok-list [msg] ok-element))))


(defmethod dispatch-crud :schedule-assoc-rm [{:keys [node-id was-last-assoc?]}]
  (let [dom-node-id (make-dashboard-node-id node-id)
        dom-node-sel (str "#" dom-node-id)]
    ;; if was last assoc and node is present, then remove it from the dashboard
    (when (and was-last-assoc? (util/element-exists? dom-node-id))
      (ef/at dom-node-sel (ef/remove-node)))))


(defmethod dispatch-crud :node-save [{:keys [node-id enabled? scheduled?] :as msg}]
  (let [dom-node-id (make-dashboard-node-id node-id)
        dom-node-sel (str "#" dom-node-id)]
    ;; remove existing one 
    (when (util/element-exists? dom-node-id)
      (ef/at dom-node-sel (ef/remove-node)))

    (when scheduled?
      (let [f (if enabled? disj conj)
            add-fn (if (executing? node-id) prepend-ok append-ok)]
        (swap! scheduled-disabled-nodes f node-id)
        (add-fn msg)))))


(defmethod dispatch-crud :default [msg]
  ;; empty!
  )


(defn process-crud-event [msg]
  (dispatch-crud msg))
