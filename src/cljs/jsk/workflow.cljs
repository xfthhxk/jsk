(ns jsk.workflow
  (:require [jsk.plumb :as plumb]
            [jsk.util :as u]
            [jsk.rfn :as rfn]
            [enfocus.core :as ef]
            [enfocus.events :as events]
            [enfocus.effects :as effects]
            [clojure.string :as string]
            [goog.string :as gstring]
            [goog.string.format]
            [jayq.core :as jq])
  (:use [jayq.core :only [$]])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))

(declare show-visualizer show-execution-workflow-details)

(def node-store (atom {}))

(defn- show-element [sel]
  (-> sel $ .show))

(defn- hide-element [sel]
  (-> sel $ .hide))

(defn- node->layout-info [n]
  (let [node-id (ef/from n (ef/get-attr :data-node-id))
        css-text (-> n .-style .-cssText)]
    {:node-id (u/str->int node-id) :css-text css-text}))


(defn- collect-workflow-layout-info []
  (let [nodes ($ ".workflow-node")]
    (doall (map node->layout-info nodes))))


(defn- show-save-success []
  (show-element "#workflow-save-success")
  (ef/at "#workflow-save-success"  (effects/fade-out 1000)))




;----------------------------------------------------------------------
; Deletes all connections from the end points and removes the
; node from the visualizer.
;----------------------------------------------------------------------
(defn delete-workflow-node [node-id ep-fail-id ep-success-id]
  (plumb/rm-endpoint ep-fail-id)
  (plumb/rm-endpoint ep-success-id)
  (plumb/rm-inbound-connections node-id)
  (-> (str "#" node-id) $ .remove))


;----------------------------------------------------------------------
; src is a string like "ep-fail-node-id-1"
; tgt is a string like "node-id-2"
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
; Saving a workflow requires a workflow name, and all nodes
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
     (show-visualizer w))))

(defn- status-id->glyph
  "Translates "
  [id]
  (case id
    1 ""                          ; unexecuted-status
    2 "glyphicon-flash"           ; started-status
    3 "glyphicon-ok"       ; finished-success
    4 "glyphicon-exclamation-sign"))  ; finished-error
    ;4 "glyphicon-fire"))  ; finished-error
    ;4 "glyphicon-warning-sign"))  ; finished-error

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
; For list available nodes with their names
;----------------------------------------------------------------------
(em/defsnippet node-list-snippet :compiled "public/templates/workflow.html" "#workflow-visualizer-node-tree" [nodes]
  "ul > :not(li:first-child)" (ef/remove-node)
  "li"  (em/clone-for [n nodes]
          (ef/do->
            (ef/set-attr :id (str (:node-id n)) :data-node-id (str (:node-id n)))
            (ef/content (:node-name n)))))

;----------------------------------------------------------------------
; Layout of the entire visualizer screen.
;----------------------------------------------------------------------
(em/defsnippet workflow-visualizer :compiled "public/templates/workflow.html" "#workflow-visualizer" [nodes workflow-id workflow-name workflow-desc enabled?]
  "#workflow-visualizer-node-explorer" (ef/content (node-list-snippet nodes))
  "#workflow-id" (ef/set-attr :value (str workflow-id))
  "#workflow-name" (ef/set-attr :value workflow-name)
  "#workflow-desc" (ef/set-attr :value workflow-desc)
  "#workflow-is-enabled" (ef/do->
                           (ef/set-prop "checked" enabled?)
                           (ef/set-attr :value (str enabled?)))
  "#workflow-save-action" (events/listen :click save-workflow))

;----------------------------------------------------------------------
; Adding a new workflow node on the visualizer.
;----------------------------------------------------------------------
(em/defsnippet workflow-node :compiled "public/templates/workflow.html" "#workflow-node" [div-id node-id node-name success-div-id fail-div-id rm-btn-id status-span-id status-id]
  "div.workflow-node"          (ef/set-attr :id div-id :data-node-id node-id)
  "button"                     (ef/do->
                                 (if (= -1 status-id)
                                   (do
                                     (ef/set-attr :id rm-btn-id :data-node-id node-id)
                                     (events/listen :click (fn[e] (delete-workflow-node div-id fail-div-id success-div-id))))
                                   (ef/remove-node)))
  "span.glyphicon"              (ef/do->
                                 (if (= -1 status-id)
                                   (ef/remove-node)
                                   (do
                                     (ef/set-attr :id status-span-id)
                                     (ef/add-class (status-id->glyph status-id))))) ; this is the status which is valid only in execution visualization
  "div.node-execution-status" (ef/do->
                                (if (= -1 status-id)
                                  (ef/remove-node)
                                  (ef/set-attr :data-ignore ""))) ; can't return nil else node disappears
  "div.workflow-node-item-name" (ef/content node-name)
  "div.ep-fail"                (ef/do->
                                (ef/set-attr :data-node-id node-id :id fail-div-id)
                                (ef/set-class (if (= -1 status-id) "ep-designer-fail" "ep-exec-visualizer-fail")))
  "div.ep-success"             (ef/do->
                                (ef/set-attr :data-node-id node-id :id success-div-id)
                                (ef/set-class (if (= -1 status-id) "ep-designer-success" "ep-exec-visualizer-success"))))



;----------------------------------------------------------------------
; Clicking on a connection for now will delete the connection.
;----------------------------------------------------------------------
(defn- connection-click-listener [cn]
  (plumb/detach-connection cn))


;----------------------------------------------------------------------
; Answers if a node is being dropped.
;----------------------------------------------------------------------
(defn- node-drop? [event ui]
  (if (-> ui .-helper (.data "node-id"))
    true
    false))


(defn- node-id->div-id
  "Constructs the div id for the whole node node."
  [node-id]
  (str "node-id-" node-id))


(defn- node-id->success-ep-id
  "Constructs the success endpoint div id"
  [node-id]
  (str "ep-success-" (node-id->div-id node-id)))

(defn- node-id->fail-ep-id
  "Constructs the fail endpoint div id"
  [node-id]
  (str "ep-fail-" (node-id->div-id node-id)))



;----------------------------------------------------------------------
; Make the delete button visible for the node (node).
;----------------------------------------------------------------------
(defn- node-hover-handler [node-sel rm-btn-sel]
  (-> node-sel $ (.mouseenter #(show-element rm-btn-sel))
                 (.mouseleave #(hide-element rm-btn-sel))))


;----------------------------------------------------------------------
; Creates a visible node in the visualizer.
; layout is the csstext property to apply to the node
;----------------------------------------------------------------------
(defn- visualizer-add-node*
  [node-id layout]
  (let [node-id-str     (str node-id)
        div-id         (node-id->div-id node-id)
        div-sel        (str "#" div-id)
        node-name       (get @node-store node-id)
        success-div-id (node-id->success-ep-id node-id)
        fail-div-id    (node-id->fail-ep-id node-id)
        rm-btn-id      (str "rm-node-id-" node-id-str)
        rm-btn-sel     (str "#" rm-btn-id)
        status-span-id nil ; for indicating its a desiger node
        status-id      -1]

    ; right now we're only supporting adding one instance of a node in the workflow
    ;(when (-> div-sel $ count zero?)

      (ef/at "#workflow-visualization-area"
        (ef/append (workflow-node div-id node-id-str node-name success-div-id fail-div-id rm-btn-id nil -1)))

      (set! (-> div-sel $ first .-style .-cssText) layout)
      (node-hover-handler div-sel rm-btn-sel)
      (hide-element rm-btn-sel)
      (plumb/draggable div-sel {:containment :parent})
      (plumb/make-success-source (str "#" success-div-id))
      (plumb/make-failure-source (str "#" fail-div-id))
      (plumb/make-target div-sel)))



;----------------------------------------------------------------------
; Fixme: This is not dropping the node where the mouse is.
;----------------------------------------------------------------------
(defn- visualizer-add-node-via-ui [event ui]
  (def the-ui ui)
  (def the-event event)
  (let [node-id (-> ui .-helper (.data "node-id") u/str->int)
        pos (-> ui .-position)
        layout (gstring/format "top: %spx; left: %spx" (.-top pos) (.-left pos))]
    (u/log (str "layout in add-node-via-ui: " layout))
    (visualizer-add-node* node-id layout)))



;----------------------------------------------------------------------
; Calls handler only if the dropped object is a node.
; When using jsPlumb to draw connections, objects are also dropped.
; This middleware ensures we create workflow nodes for node.
;----------------------------------------------------------------------
(defn- with-node-drop [handler]
  (fn [event ui]
    (if (node-drop? event ui)
      (handler event ui))))

(def visualizer-add-node (with-node-drop visualizer-add-node-via-ui))


; The data in the input map is like:
; {:to-node-layout "top: 142px; left: 50.5px;"
;  :from-node-layout "top: 30px; left: 39.5px;"
;  :to-node-type-id 1
;  :to-node-name "run ls"
;  :from-node-type-id 1
;  :from-node-name "cal node"
;  :success true
;  :to-node-id 2
;  :from-node-id 1}
(defn- add-one-edge [{:keys[src-id dest-id src-layout dest-layout success]}]
  (let [src-div-id (node-id->div-id src-id)
        dest-div-id   (node-id->div-id dest-id)]

    (when (u/element-not-exists? src-div-id)
      (visualizer-add-node* src-id src-layout))

    (when (u/element-not-exists? dest-div-id)
      (visualizer-add-node* dest-id dest-layout))

  (let [id-mkr (if success node-id->success-ep-id node-id->fail-ep-id)
        src-ep-id (id-mkr src-id)]
    (u/log (str "making connection src: " src-ep-id ", to: " dest-div-id))
    (plumb/connect src-ep-id dest-div-id))))

(defn- reconstruct-ui [data]
  (doseq [m data]
    (add-one-edge m)))



;----------------------------------------------------------------------
; Shows a new workflow visualizer.
; -- Get and build the ui for all node in the system
;----------------------------------------------------------------------
(defn show-visualizer
  ([] (show-visualizer {:workflow-id -1 :workflow-name "" :workflow-desc "" :is-enabled true}))
  ([{:keys [workflow-id workflow-name workflow-desc is-enabled] :as w}]
    (go
      (let [nodes (<! (rfn/fetch-all-nodes))
            graph (<! (rfn/fetch-workflow-graph workflow-id))]

        (reset! node-store (reduce #(conj %1 ((juxt :node-id :node-name) %2)) {} nodes))
        (plumb/reset) ; clear any state it may have had
        (u/showcase (workflow-visualizer nodes workflow-id workflow-name workflow-desc is-enabled))

        (plumb/register-connection-click-handler connection-click-listener)

        ; these things can happen only after the workflow visualizer is displayed
        (hide-element "#workflow-save-success")
        (plumb/default-container :#workflow-visualization-area)
        (-> :#workflow-visualization-area $ (.droppable (clj->js {:accept ".available-node" :activeClass :ui-state-highlight})))
        (-> :#workflow-visualization-area $ (.on "drop" visualizer-add-node))
        (-> ".available-node" $ (.draggable (clj->js {:revert true :helper :clone :opacity 0.35})))
        (reconstruct-ui graph)))))




(defn show-workflows []
  (go
   (let [ww (<! (rfn/fetch-all-workflows))]
     (u/showcase (list-workflows ww)))))



;----------------------------------------------------------------------
; Layout the readonly visualization area.
;----------------------------------------------------------------------
(em/defsnippet execution-visualizer :compiled "public/templates/workflow.html" "#workflow-visualizer" [exec-info]
  "#workflow-save-success" (ef/remove-node)
  "#workflow-visualizer-node-explorer" (ef/remove-node)
  "#workflow-save-form" (ef/remove-node))



;----------------------------------------------------------------------
; Creates a visible node in the visualizer.
; layout is the csstext property to apply to the node
;----------------------------------------------------------------------
(defn- execution-visualizer-add-node
  [node-id node-name node-type exec-wf-id status-id layout]
  (let [node-id-str     (str node-id)
        div-id         (node-id->div-id node-id)
        div-sel        (str "#" div-id)
        success-div-id (node-id->success-ep-id node-id)
        fail-div-id    (node-id->fail-ep-id node-id)
        rm-btn-id      (str "rm-node-id-" node-id-str)
        rm-btn-sel     (str "#" rm-btn-id)
        status-span-id (str "execution-status-node-id-" node-id)]

    ; right now we're only supporting adding one instance of a node in the workflow
    ;(when (-> div-sel $ count zero?)

    (ef/at "#workflow-visualization-area"
      (ef/append (workflow-node div-id node-id-str node-name success-div-id fail-div-id rm-btn-id status-span-id status-id)))

      ; remove rm btn in
    ;(ef/at rm-btn-sel (ef/remove-node))

    ; for workflow nodes add a click listener which takes you to the execution workflow
    (when exec-wf-id
      (ef/at div-sel (ef/do->
                        (ef/add-class "drill-down-workflow-node")
                        (events/listen :click (fn[e] (show-execution-workflow-details exec-wf-id))))))

    (set! (-> div-sel $ first .-style .-cssText) layout)
    (plumb/draggable div-sel {:containment :parent})
    (plumb/make-success-source (str "#" success-div-id))
    (plumb/make-failure-source (str "#" fail-div-id))
    ;(plumb/disable-endpoint-dnd (str "#" success-div-id))
    ;(plumb/disable-endpoint-dnd (str "#" fail-div-id))
    (plumb/make-target div-sel)))


(defn- add-one-execution-edge [{:keys[src-vertex-id src-node-name src-layout src-node-type src-runs-execution-workflow-id src-status-id
                            dest-vertex-id dest-node-name dest-layout dest-node-type dest-runs-execution-workflow-id dest-status-id success]}]
  (let [src-div-id (node-id->div-id src-vertex-id)
        dest-div-id   (node-id->div-id dest-vertex-id)]

    (if (u/element-not-exists? src-div-id)
      (execution-visualizer-add-node src-vertex-id src-node-name src-node-type src-runs-execution-workflow-id src-status-id src-layout))

    (if (and dest-vertex-id (u/element-not-exists? dest-div-id))
      (execution-visualizer-add-node dest-vertex-id dest-node-name dest-node-type dest-runs-execution-workflow-id dest-status-id dest-layout))

    (if dest-vertex-id
      (let [id-mkr (if success node-id->success-ep-id node-id->fail-ep-id)
            src-ep-id (id-mkr src-vertex-id)]
        (u/log (str "making connection src: " src-ep-id ", to: " dest-div-id))
        (plumb/connect src-ep-id dest-div-id)))))


(defn- construct-execution-ui [{:keys[nodes wf-info]}]
  (doseq [m nodes]
    (add-one-execution-edge m)))



(defn- show-execution-workflow-details [exec-wf-id]
  (u/log (str"exec-wf-id is:" exec-wf-id))
  (go
   (let [exec-wf-info (<! (rfn/fetch-execution-workflow-details exec-wf-id))]
    (plumb/reset) ; clear any state it may have had
    (u/showcase (execution-visualizer exec-wf-info))
    (plumb/default-container :#workflow-visualization-area)
    (construct-execution-ui exec-wf-info))))

(defn show-execution-visualizer
  [execution-id]
  (u/log (str "execution-id is:" execution-id))
  (go
   (let [{:keys[root-execution-workflow-id]:as exec-info}
         (<! (rfn/fetch-execution-details execution-id))]
     (show-execution-workflow-details root-execution-workflow-id))))





























