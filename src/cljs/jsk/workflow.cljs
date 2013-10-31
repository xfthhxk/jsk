(ns jsk.workflow
  (:require [jsk.plumb :as plumb]
            [jsk.util :as u]
            [jsk.rfn :as rfn]
            [enfocus.core :as ef]
            [enfocus.events :as events]
            [jayq.core :as jq])
  (:use [jayq.core :only [$]])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))

(def job-store (atom {}))

(em/defsnippet test-snippet :compiled "public/templates/workflow.html" "#workflow-test" []
  "#j1" (ef/content "From enfocus job 1")
  "#j2" (ef/content "From enfocus job 2")
  "#j3" (ef/content "From enfocus job 3"))


(defn show-test []
  (u/log "in workflow show test")
  (u/showcase (test-snippet))

  (plumb/default-container :#workflow-test)
  (plumb/draggable :.workflow-node)

  (plumb/make-success-source :#ep-success-1)
  (plumb/make-success-source :#ep-success-2)
  (plumb/make-success-source :#ep-success-3)

  (plumb/make-failure-source :#ep-fail-1)
  (plumb/make-failure-source :#ep-fail-2)
  (plumb/make-failure-source :#ep-fail-3)

  (plumb/make-target-alt)


  (plumb/repaint!))


(em/defsnippet job-list-snippet :compiled "public/templates/workflow.html" "#workflow-designer-job-tree" [jobs]
  "ul > :not(li:first-child)" (ef/remove-node)
  "li"  (em/clone-for [j jobs]
          (ef/do->
            (ef/set-attr :id (str (:job-id j)) :data-job-id (str (:job-id j)))
            (ef/content (:job-name j)))))


(em/defsnippet workflow-designer :compiled "public/templates/workflow.html" "#workflow-designer" [jobs]
  "#workflow-designer-job-explorer" (ef/content (job-list-snippet jobs)))

(em/defsnippet workflow-node :compiled "public/templates/workflow.html" "#workflow-node" [div-id job-id job-name success-div-id fail-div-id]
  "div.workflow-node"          (ef/set-attr :id div-id :data-job-id job-id)
  "div.workflow-node-job-name" (ef/content job-name)
  "div.ep-fail"                (ef/set-attr :data-job-id job-id :id fail-div-id)
  "div.ep-success"             (ef/set-attr :data-job-id job-id :id success-div-id))



(defn- job-drop? [event ui]
  (if (-> ui .-helper (.data "job-id"))
    true
    false))


(defn- designer-add-job* [event ui]
  (let [job-id-str (-> ui .-helper (.data "job-id"))
        div-id     (str "job-id-" job-id-str)
        div-sel    (str "#" div-id)
        job-id     (u/str->int job-id-str)
        job-name   (get @job-store job-id)
        success-div-id (str "ep-success-" div-id)
        fail-div-id (str "ep-fail-" div-id)]
    (ef/at "#workflow-design-area" (ef/append (workflow-node div-id job-id-str job-name success-div-id fail-div-id)))
    (-> div-sel $ (.offset (-> ui .-offset)))
    (plumb/draggable div-sel {:containment :parent})
    (plumb/make-success-source (str "#" success-div-id))
    (plumb/make-failure-source (str "#" fail-div-id))
    (plumb/make-target div-sel)))


(defn- with-job-drop [handler]
  (fn [event ui]
    (if (job-drop? event ui)
      (handler event ui))))

(def designer-add-job (with-job-drop designer-add-job*))


;----------------------------------------------------------------------
; Shows a new workflow designer.
; -- Get and build the ui for all jobs in the system
;----------------------------------------------------------------------
(defn show-designer []
  (go
    (let [jobs (<! (rfn/fetch-all-jobs))]
      (reset! job-store (reduce #(conj %1 ((juxt :job-id :job-name) %2)) {} jobs))
      (u/showcase (workflow-designer jobs))

      ; these things can happen only after the workflow designer is displayed
      (plumb/default-container :#workflow-design-area)
      (-> :#workflow-design-area $ (.droppable (clj->js {:accept ".available-job" :activeClass :ui-state-highlight})))
      (-> :#workflow-design-area $ (.on "drop" designer-add-job))
      (-> ".available-job" $ (.draggable (clj->js {:revert true :helper :clone :opacity 0.35}))))))

