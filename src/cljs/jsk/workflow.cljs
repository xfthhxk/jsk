(ns jsk.workflow
  (:require [jsk.plumb :as plumb]
            [jsk.util :as u]
            [jsk.rfn :as rfn]
            [enfocus.core :as ef]
            [enfocus.events :as events]
            [enfocus.effects :as effects]
            [clojure.string :as string]
            [jayq.core :as jq])
  (:use [jayq.core :only [$]])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))
(declare show-designer)

(def job-store (atom {}))

(defn- show-element [sel]
  (-> sel $ .show))

(defn- hide-element [sel]
  (-> sel $ .hide))

(defn- node->layout-info [n]
  (let [job-id (ef/from n (ef/get-attr :data-job-id))
        css-text (-> n .-style .-cssText)]
    {:job-id (u/str->int job-id) :css-text css-text}))


(defn- collect-workflow-layout-info []
  (let [nodes ($ ".workflow-node")]
    (doall (map node->layout-info nodes))))


(defn- show-save-success []
  (show-element "#workflow-save-success")
  (ef/at "#workflow-save-success"  (effects/fade-out 1000)))



;----------------------------------------------------------------------
; Deletes all connections from the end points and removes the
; node from the designer.
;----------------------------------------------------------------------
(defn delete-workflow-node [node-id ep-fail-id ep-success-id]
  (plumb/rm-endpoint ep-fail-id)
  (plumb/rm-endpoint ep-success-id)
  (plumb/rm-inbound-connections node-id)
  (-> (str "#" node-id) $ .remove))


;----------------------------------------------------------------------
; src is a string like "ep-fail-job-id-1"
; tgt is a string like "job-id-2"
; Returns {:success? false :src-node-id 1 :tgt-node-id 2}
;----------------------------------------------------------------------
(defn- parse-connection-info [{:keys [src tgt]}]
  (u/log (str "src: " src ", tgt: " tgt))
  (let [tt (-> src (string/split #"-"))
        status (second tt)
        src-id (last tt)
        tgt-id (-> tgt (string/split #"-") last)]
    (u/log (str "status: " status ", src-id: " src-id ", tgt-id: " tgt-id))
    {:success? (= "success" status)
     :src-node-id (u/str->int src-id)
     :tgt-node-id (u/str->int tgt-id)}))



(defn- read-workflow-form []
  (let [form (ef/from "#workflow-save-form" (ef/read-form))
        data (u/update-str->int form :workflow-id)
        data1 (assoc data :is-enabled (u/element-checked? "workflow-is-enabled"))]
    (u/log (str "Form data is: " form))
    data1))


(defn- collect-workflow-data []
  (let [form (read-workflow-form)
        cn (map parse-connection-info (plumb/connections->map))]
    (assoc form :connections cn)))


;----------------------------------------------------------------------
; Saving a workflow requires a workflow name, and all jobs
; and connections from jsPlumb.
;----------------------------------------------------------------------
(defn save-workflow [event]
  (go
   (let [workflow-info (collect-workflow-data)
         layout-info   (collect-workflow-layout-info)
         data {:workflow workflow-info :layout layout-info}
         {:keys [success? schedule-id errors] :as save-result} (<! (rfn/save-workflow data))]
     (if success?
       (show-save-success)
       (u/display-errors (-> errors vals flatten))))))

(defn trigger-workflow-now [e]
  (.stopPropagation e)
  (let [source (u/event-source e)
        wf-id (ef/from source (ef/get-attr :data-workflow-id))]
    (rfn/trigger-workflow-now wf-id)))


(defn show-workflow-edit [w])

(defn workflow-row-clicked [e]
  (go
   (let [id (ef/from (u/event-source e) (ef/get-attr :data-workflow-id))
         w (<! (rfn/fetch-workflow-details id))]
     (show-designer w))))

;----------------------------------------------------------------------
; Lists all workflows.
;----------------------------------------------------------------------
(em/defsnippet list-workflows :compiled "public/templates/workflow.html" "#workflow-list" [ww]
  "tbody > :not(tr:first-child)" (ef/remove-node)
  "tbody > tr" (em/clone-for [w ww]
                 "td.workflow-id" #(ef/at (u/parent-node %1)
                                          (ef/do->
                                            (ef/set-attr :data-workflow-id (str (:workflow-id w)))
                                            (events/listen :click workflow-row-clicked)))
                 "td.workflow-id" (ef/content (str (:workflow-id w)))
                 "td.workflow-name" (ef/content (:workflow-name w))
                 "td.workflow-is-enabled" (ef/content (str (:is-enabled w)))
                 "td.workflow-trigger-now > button" (ef/do->
                                                       (ef/set-attr :data-workflow-id (str (:workflow-id w)))
                                                       (events/listen :click trigger-workflow-now))))


;----------------------------------------------------------------------
; For list available jobs with their names
;----------------------------------------------------------------------
(em/defsnippet job-list-snippet :compiled "public/templates/workflow.html" "#workflow-designer-job-tree" [jobs]
  "ul > :not(li:first-child)" (ef/remove-node)
  "li"  (em/clone-for [j jobs]
          (ef/do->
            (ef/set-attr :id (str (:job-id j)) :data-job-id (str (:job-id j)))
            (ef/content (:job-name j)))))

;----------------------------------------------------------------------
; Layout of the entire designer screen.
;----------------------------------------------------------------------
(em/defsnippet workflow-designer :compiled "public/templates/workflow.html" "#workflow-designer" [jobs workflow-id workflow-name workflow-desc enabled?]
  "#workflow-designer-job-explorer" (ef/content (job-list-snippet jobs))
  "#workflow-id" (ef/set-attr :value (str workflow-id))
  "#workflow-name" (ef/set-attr :value workflow-name)
  "#workflow-desc" (ef/set-attr :value workflow-desc)
  "#workflow-is-enabled" (ef/do->
                           (ef/set-prop "checked" enabled?)
                           (ef/set-attr :value (str enabled?)))
  "#workflow-save-action" (events/listen :click save-workflow))

;----------------------------------------------------------------------
; Adding a new workflow node (job) on the designer.
;----------------------------------------------------------------------
(em/defsnippet workflow-node :compiled "public/templates/workflow.html" "#workflow-node" [div-id job-id job-name success-div-id fail-div-id rm-btn-id]
  "div.workflow-node"          (ef/set-attr :id div-id :data-job-id job-id)
  "button"                     (ef/do->
                                 (ef/set-attr :id rm-btn-id :data-job-id job-id)
                                 (events/listen :click (fn[e] (delete-workflow-node div-id fail-div-id success-div-id))))
  "div.workflow-node-job-name" (ef/content job-name)
  "div.ep-fail"                (ef/set-attr :data-job-id job-id :id fail-div-id)
  "div.ep-success"             (ef/set-attr :data-job-id job-id :id success-div-id))


;----------------------------------------------------------------------
; Clicking on a connection for now will delete the connection.
;----------------------------------------------------------------------
(defn- connection-click-listener [cn]
  (plumb/detach-connection cn))


;----------------------------------------------------------------------
; Answers if a job is being dropped.
;----------------------------------------------------------------------
(defn- job-drop? [event ui]
  (if (-> ui .-helper (.data "job-id"))
    true
    false))


(defn- job-id->div-id
  "Constructs the div id for the whole job node."
  [job-id]
  (str "job-id-" job-id))


(defn- job-id->success-ep-id
  "Constructs the success endpoint div id"
  [job-id]
  (str "ep-success-" (job-id->div-id job-id)))

(defn- job-id->fail-ep-id
  "Constructs the fail endpoint div id"
  [job-id]
  (str "ep-fail-" (job-id->div-id job-id)))



;----------------------------------------------------------------------
; Make the delete button visible for the node (job).
;----------------------------------------------------------------------
(defn- node-hover-handler [node-sel rm-btn-sel]
  (-> node-sel $ (.mouseenter #(show-element rm-btn-sel))
                 (.mouseleave #(hide-element rm-btn-sel))))


;----------------------------------------------------------------------
; Creates a visible node in the designer.
; layout is the csstext property to apply to the node
;----------------------------------------------------------------------
(defn- designer-add-job*
  [job-id layout]
  (let [job-id-str     (str job-id)
        div-id         (job-id->div-id job-id)
        div-sel        (str "#" div-id)
        job-name       (get @job-store job-id)
        success-div-id (job-id->success-ep-id job-id)
        fail-div-id    (job-id->fail-ep-id job-id)
        rm-btn-id      (str "rm-job-id-" job-id-str)
        rm-btn-sel     (str "#" rm-btn-id)]

    ; right now we're only supporting adding one instance of a job in the workflow
    ;(when (-> div-sel $ count zero?)

      (ef/at "#workflow-design-area"
        (ef/append (workflow-node div-id job-id-str job-name success-div-id fail-div-id rm-btn-id)))

      (set! (-> div-sel $ first .-style .-cssText) layout)
      (node-hover-handler div-sel rm-btn-sel)
      (hide-element rm-btn-sel)
      (plumb/draggable div-sel {:containment :parent})
      (plumb/make-success-source (str "#" success-div-id))
      (plumb/make-failure-source (str "#" fail-div-id))
      (plumb/make-target div-sel)))



;----------------------------------------------------------------------
; Fixme: This is not dropping the job where the mouse is.
;----------------------------------------------------------------------
(defn- designer-add-job-via-ui [event ui]
  (def the-ui ui)
  (def the-event event)
  (let [job-id (-> ui .-helper (.data "job-id") u/str->int)
        pos (-> ui .-position)
        layout (format "top: %spx; left: %spx" (.-top pos) (.-left pos))]
    (u/log (str "layout in add-job-via-ui: " layout))
    (designer-add-job* job-id layout)))



;----------------------------------------------------------------------
; Calls handler only if the dropped object is a job.
; When using jsPlumb to draw connections, objects are also dropped.
; This middleware ensures we create workflow nodes for jobs.
;----------------------------------------------------------------------
(defn- with-job-drop [handler]
  (fn [event ui]
    (if (job-drop? event ui)
      (handler event ui))))

(def designer-add-job (with-job-drop designer-add-job-via-ui))


; The data in the input map is like:
; {:to-node-layout "top: 142px; left: 50.5px;"
;  :from-node-layout "top: 30px; left: 39.5px;"
;  :to-node-type-id 1
;  :to-node-name "run ls"
;  :from-node-type-id 1
;  :from-node-name "cal job"
;  :success true
;  :to-node-id 2
;  :from-node-id 1}
(defn- add-one-edge [{:keys[src-id dest-id src-layout dest-layout success]}]
  (let [src-div-id (job-id->div-id src-id)
        dest-div-id   (job-id->div-id dest-id)]

    (when (u/element-not-exists? src-div-id)
      (designer-add-job* src-id src-layout))

    (when (u/element-not-exists? dest-div-id)
      (designer-add-job* dest-id dest-layout))

  (let [id-mkr (if success job-id->success-ep-id job-id->fail-ep-id)
        src-ep-id (id-mkr src-id)]
    (u/log (str "making connection src: " src-ep-id ", to: " dest-div-id))
    (plumb/connect src-ep-id dest-div-id))))

(defn- reconstruct-ui [data]
  (doseq [m data]
    (add-one-edge m)))



;----------------------------------------------------------------------
; Shows a new workflow designer.
; -- Get and build the ui for all jobs in the system
;----------------------------------------------------------------------
(defn show-designer
  ([] (show-designer {:workflow-id -1 :workflow-name "" :workflow-desc "" :is-enabled true}))
  ([{:keys [workflow-id workflow-name workflow-desc is-enabled] :as w}]
    (go
      (let [jobs (<! (rfn/fetch-all-jobs))
            graph (<! (rfn/fetch-workflow-graph workflow-id))]

        (reset! job-store (reduce #(conj %1 ((juxt :job-id :job-name) %2)) {} jobs))
        (plumb/reset) ; clear any state it may have had
        (u/showcase (workflow-designer jobs workflow-id workflow-name workflow-desc is-enabled))

        (plumb/register-connection-click-handler connection-click-listener)

        ; these things can happen only after the workflow designer is displayed
        (hide-element "#workflow-save-success")
        (plumb/default-container :#workflow-design-area)
        (-> :#workflow-design-area $ (.droppable (clj->js {:accept ".available-job" :activeClass :ui-state-highlight})))
        (-> :#workflow-design-area $ (.on "drop" designer-add-job))
        (-> ".available-job" $ (.draggable (clj->js {:revert true :helper :clone :opacity 0.35})))
        (reconstruct-ui graph)))))




(defn show-workflows []
  (go
   (let [ww (<! (rfn/fetch-all-workflows))]
     (u/showcase (list-workflows ww)))))
