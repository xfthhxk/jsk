(ns jsk.workflow
  (:require [jsk.plumb :as plumb]
            [jsk.util :as u]
            [jsk.rfn :as rfn]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:use [jayq.core :only [$ delegate toggle]])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))


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
            (ef/set-attr :id (str (:job-id j)))
            (ef/content (:job-name j)))))


(em/defsnippet workflow-designer :compiled "public/templates/workflow.html" "#workflow-designer" [jobs]
  "#workflow-designer-job-explorer" (ef/content (job-list-snippet jobs)))


(defn- add-job-to-designer [event, ui]
  (u/log "item was dropped"))

;----------------------------------------------------------------------
; Shows a new workflow designer.
; -- Get and build the ui for all jobs in the system
;----------------------------------------------------------------------
(defn show-designer []
  (go
    (let [jobs (<! (rfn/fetch-all-jobs))]
      (u/showcase (workflow-designer jobs))
      (-> :#workflow-design-area $ (.droppable (clj->js {:accept ".available-job" :activeClass :ui-state-highlight})))
      (-> :#workflow-design-area $ (.on "drop" add-job-to-designer))
      (-> ".available-job" $ (.draggable (clj->js {:revert true :helper :clone :opacity 0.35}))))))

