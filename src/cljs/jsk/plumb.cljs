(ns jsk.plumb
  (:require [enfocus.core :as ef]
            [jayq.util :as ju]
            [enfocus.events :as events])
  (:use [jayq.core :only [$ ->selector]]))

; an arbitarily large number
(def max-connections 999)

;-----------------------------------------------------------------------
; Definitions governing success endpoints
; Lines are green and solid indicating succcess job link.
;-----------------------------------------------------------------------
(def ^:private success-endpoint-options
  (clj->js 
    {:anchor :Continuous
     :connector [:StateMachine {:curviness 20}]
     :connectorStyle {:strokeStyle :green :lineWidth 2 :outlineColor "transparent" :outlineWidth 4}}))

;-----------------------------------------------------------------------
; Definitions governing failure endpoints
; Lines are red and broken indicating failed job link.
;-----------------------------------------------------------------------
(def ^:private failure-endpoint-options
  (clj->js 
    {:anchor :Continuous
     :connector [:StateMachine {:curviness 20}]
     :connectorStyle {:strokeStyle :red :lineWidth 2 :dashstyle "2 2" :outlineColor "transparent" :outlineWidth 4}}))

(defn import-defaults [jsp]
  (let [data {:ConnectionOverlays [[:Arrow {:location 1 :id :arrow :length 14 :width 9 :foldback 0.7}]]
              :Endpoint           [:Dot {:radius 2}]
              :HoverPaintStyle    {:strokeStyle "#1e8151" :lineWidth 2}}]
    (println "defaults: " data)
    (-> jsp (.importDefaults (clj->js data)))))



(defn init[]
  (def js-plumb (-> js/window .-jsPlumb))
  (import-defaults js-plumb))


(defn- register-event-handler [event cb]
  (-> js-plumb (.bind event cb)))

;----------------------------------------------------------------------
; Register connection click listener. The callback function
; receives the connection.
;----------------------------------------------------------------------
(defn register-connection-click-handler [cb]
  (register-event-handler "click" cb))

;----------------------------------------------------------------------
; Register connection created listener. The callback function
; receives the connection info and the original event.
;----------------------------------------------------------------------
(defn register-connection-created-handler [cb]
  (register-event-handler "connection" cb))

(defn detach-connection [cn]
  (-> js-plumb (.detach cn)))



;----------------------------------------------------------------------
; Lookup the element specified by the element-id
; and detach all connections.
;----------------------------------------------------------------------
(defn detach-endpoint-connections [element-id]
  (-> js-plumb (.detachAllConnections element-id)))

;----------------------------------------------------------------------
; Lookup the element specified by the element-id
; and deletes it including all connections.
;----------------------------------------------------------------------
(defn rm-endpoint [element-id]
  (detach-endpoint-connections element-id)
  (-> js-plumb (.deleteEndpoint element-id)))

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
  ([src tgt]
   (connect {:source src :target tgt}))
  ([data]
   (-> js-plumb (.connect (clj->js data)))))


;-----------------------------------------------------------------------
; Answers with all connections.
;-----------------------------------------------------------------------
(defn connections []
  (-> js-plumb .getAllConnections))

  
(defn inbound-connections [element-id]
  (-> js-plumb (.getConnections (clj->js {:target element-id}))))

(defn outbound-connections [element-id]
  (-> js-plumb (.getConnections (clj->js {:source element-id}))))

;; answers true if there are any inbound or outbound connections
;; node-id is an integer representing the node
(defn connected? [element-id]
  (->> element-id
       ((juxt inbound-connections outbound-connections))
       (map count)
       (reduce +)
       (not= 0)))

(defn rm-inbound-connections [element-id]
  (doseq [cn (inbound-connections element-id)]
    (detach-connection cn)))

(defn detach-all-connections []
  (doseq [cn (connections)]
    (detach-connection cn)))

;-----------------------------------------------------------------------
; Answers with a seq of all connections. Each connection is represented
; as a map with a :src and :tgt keys.
;-----------------------------------------------------------------------
(defn connections->map []
  (map (fn[c]
         {:src (-> c .-sourceId) :tgt (-> c .-targetId)})
       (connections)))


(defn endpoints []
  (-> js-plumb .selectEndpoints))


;-----------------------------------------------------------------------
; Endpoint
;-----------------------------------------------------------------------
;(defn add-endpoint [id]
;  (-> js-plumb (.addEndpoint (->selector id) (clj->js endpoint-options))))

;(defn make-source [id]
;  (-> js-plumb (.makeSource ($ id) (clj->js endpoint-options))))

;; (defn make-source-alt []
;;   (-> js-plumb (.makeSource ($ :.ep) (clj->js success-endpoint-options)))
;;   (-> js-plumb (.makeSource ($ :.ep-fail) (clj->js failure-endpoint-options))))

(defn- make-source [selector options]
  (-> js-plumb (.makeSource ($ selector) options)))

(defn make-failure-source [selector]
  (make-source selector failure-endpoint-options))

(defn make-success-source [selector]
  (make-source selector success-endpoint-options))


 ; doesn't seem to work
(defn disable-endpoint-dnd
  "Disables drag n drop connections from this endpoint."
  [selector]
  (-> js-plumb (.setEnabled ($ selector) false)))


(defn make-target [id]
  (-> js-plumb (.makeTarget ($ id) (clj->js {:dropOptions {:hoverClass :dragHover}
                                             :anchor :Continuous}))))

(defn make-target-alt []
  (-> js-plumb (.makeTarget ($ :.workflow-node) (clj->js {:dropOptions {:hoverClass :dragHover}
                                             :anchor :Continuous}))))

(defn draggable
  ([selector] (draggable selector {}))
  ([selector opt-map]
    (-> js-plumb (.draggable ($ selector) (clj->js opt-map)))))

(defn repaint! []
  (-> js-plumb .repaintEverything))


(defn reset []
  (-> js-plumb .reset))
