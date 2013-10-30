(ns jsk.plumb
  (:require [enfocus.core :as ef]
            [jayq.util :as ju]
            [enfocus.events :as events])
  (:use [jayq.core :only [$ ->selector]]))

; an arbitarily large number
(def max-connections 999)

(defn import-defaults1 [jsp]
  (let [data {:Anchors        [:LeftCenter :RightCenter]
              :ConnectionOverlays [[:Arrow {:location 1 :id :arrow :length 14 :foldback 0.7}]]
              :DragOptions    {:cursor :pointer :zIndex 2000}
              :Endpoint       [:Dot {:radius 2}]
              :HoverPaintStyle {:strokeStyle "#1e8151" :lineWidth 2}
              :EndpointStyle  {:width 20 :height 16 :strokeStyle "#666"}
              :MaxConnections max-connections
              :PaintStyle     {:strokeStyle "#666" :lineWidth 2}}]
    (ju/log "defaults: " (clj->js data))
    (-> jsp (.importDefaults (clj->js data)))))

;-----------------------------------------------------------------------
; Definitions governing success endpoints
; Lines are green and solid indicating succcess job link.
;-----------------------------------------------------------------------
(def success-endpoint-options
  {:anchor :Continuous
   :connector [:StateMachine {:curviness 20}]
   :connectorStyle {:strokeStyle :green :lineWidth 2 :outlineColor "transparent" :outlineWidth 4}})

;-----------------------------------------------------------------------
; Definitions governing failure endpoints
; Lines are red and broken indicating failed job link.
;-----------------------------------------------------------------------
(def failure-endpoint-options
  {:anchor :Continuous
   :connector [:StateMachine {:curviness 20}]
   :connectorStyle {:strokeStyle :red :lineWidth 2 :dashstyle "2 2" :outlineColor "transparent" :outlineWidth 4}})

(defn import-defaults [jsp]
  (let [data {:ConnectionOverlays [[:Arrow {:location 1 :id :arrow :length 14 :width 9 :foldback 0.7}]]
              :Endpoint           [:Dot {:radius 2}]
              :HoverPaintStyle    {:strokeStyle "#1e8151" :lineWidth 2}}]
    (ju/log "defaults: " (clj->js data))
    (-> jsp (.importDefaults (clj->js data)))))



(defn init[]
  (def js-plumb (-> js/window .-jsPlumb))
  (ju/log "init, js-plumb is: " js-plumb)
  (import-defaults js-plumb))

(defn default-container
  ([]
   "Retrieves the default container elements are added to."
   (-> js-plumb .-Defaults .-Container))
  ([id]
   "Sets the default container elements are added to. 'id' is the
    a keyword id for the element eg: :#my-div-id"
    (set! (-> js-plumb .-Defaults .-Container) ($ id))))


(defn suspend-drawing
  ([suspend?]
   "Suspend drawing?"
   (-> js-plumb (.setSuspendDrawing suspend?)))

  ([suspend? repaint?]
   "Suspend drawing? followed by repaint?"
   (-> js-plumb (.setSuspendDrawing suspend?))))


(defn do-while-suspended
  "Performs actions specified by f with drawing suspended.
   Enables drawing after calling f."
  ([f] (do-while-suspended f true))
  ([f repaint?]
    ; by default the repaint? is not to repaint so negate it
    (-> js-plumb (.doWhileSuspended f (not repaint?)))))



;-----------------------------------------------------------------------
; Connections
;-----------------------------------------------------------------------
(defn connect
  [data]
  (-> js-plumb (.connect (clj->js data))))


;-----------------------------------------------------------------------
; Endpoint
;-----------------------------------------------------------------------
;(defn add-endpoint [id]
;  (-> js-plumb (.addEndpoint (->selector id) (clj->js endpoint-options))))

;(defn make-source [id]
;  (ju/log "make-source, js-plumb is: " js-plumb)
;  (-> js-plumb (.makeSource ($ id) (clj->js endpoint-options))))

(defn make-source-alt []
  (-> js-plumb (.makeSource ($ :.ep) (clj->js success-endpoint-options)))
  (-> js-plumb (.makeSource ($ :.ep-fail) (clj->js failure-endpoint-options))))

(defn make-source-1 [selector options]
  (-> js-plumb (.makeSource ($ selector) (clj->js options))))

(defn make-failure-source [selector]
  (make-source-1 selector failure-endpoint-options))
  ;(-> js-plumb (.makeSource ($ :.ep-fail) (clj->js failure-endpoint-options))))

(defn make-success-source [selector]
  (make-source-1 selector success-endpoint-options))
  ;(-> js-plumb (.makeSource ($ selector) (clj->js success-endpoint-options))))


(defn make-target [id]
  (-> js-plumb (.makeTarget ($ id) (clj->js {:dropOptions {:hoverClass :dragHover}
                                             :anchor :Continuous}))))

(defn make-target-alt []
  (-> js-plumb (.makeTarget ($ :.w) (clj->js {:dropOptions {:hoverClass :dragHover}
                                             :anchor :Continuous}))))

(defn draggable [selector]
  (-> js-plumb (.draggable ($ selector))))

(defn repaint! []
  (-> js-plumb .repaintEverything))
