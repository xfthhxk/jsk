(ns jsk.workflow
  (:require [jsk.plumb :as plumb]
            [jsk.util :as ju]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:use [jayq.core :only [$ delegate toggle]])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))


(em/defsnippet test-snippet :compiled "public/templates/workflow.html" "#workflow-designer" []
  "#j1" (ef/content "From enfocus job 1")
  "#j2" (ef/content "From enfocus job 2")
  "#j3" (ef/content "From enfocus job 3"))


(defn show-test []
  (ju/log "in workflow show test")
  (ju/showcase (test-snippet))

  (plumb/default-container :#workflow-designer)
  (plumb/draggable :.w)

  (plumb/make-success-source :#ep1)
  (plumb/make-success-source :#ep2)
  (plumb/make-success-source :#ep3)

  (plumb/make-failure-source :#ep-fail-1)
  (plumb/make-failure-source :#ep-fail-2)
  (plumb/make-failure-source :#ep-fail-3)

  (plumb/make-target-alt)


  (plumb/repaint!))

