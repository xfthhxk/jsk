(ns modern-cljs.modern
  (:require [jsk.plumb :as plumb]
            [jayq.util :as ju]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:use [jayq.core :only [$ delegate toggle]]))


(defn init []
  (ju/log "Init called")
  (plumb/init)

  (plumb/default-container :#container)

  (plumb/draggable :.w)

  (plumb/make-success-source :#ep1)
  (plumb/make-success-source :#ep2)
  (plumb/make-success-source :#ep3)

  (plumb/make-failure-source :#ep-fail-1)
  (plumb/make-failure-source :#ep-fail-2)
  (plumb/make-failure-source :#ep-fail-3)

  (plumb/make-target-alt)


  (plumb/repaint!))


(set! (.-onload js/window) init)
