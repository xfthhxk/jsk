(ns jsk.client
    (:require [enfocus.core :as ef]
              [enfocus.effects :as effects]
              [enfocus.events :as events]
              [clojure.browser.repl :as repl]
              [cljs.core.async :as async :refer [<!]]
              [jsk.plumb :as plumb]
              [jsk.explorer :as explorer]
              [jsk.dashboard :as dashboard]
              [jsk.rpc :as rpc]
              [jsk.util :as ju]
              [jsk.job :as j]
              [jsk.workflow :as workflow]
              [jsk.agent :as agent]
              [jsk.executions :as executions]
              [jsk.search :as search]
              [jsk.workflow :as w]
              [jsk.schedule :as s])
    (:require-macros [cljs.core.async.macros :refer [go]])
    (:use-macros [enfocus.macros :only [deftemplate defsnippet defaction]]))


;-----------------------------------------------------------------------
; Dev stuff
;-----------------------------------------------------------------------
(def dev-mode false)

(defn repl-connect []
 (when dev-mode
   (repl/connect "http://localhost:9000/repl")))

;-----------------------------------------------------------------------
; Generic error handler for RPCs.
; Just logs the error and sets the msg in the error-div.
;-----------------------------------------------------------------------
(defn rpc-error-handler [status msg]
  (println "ERROR status: " status ", msg: " msg)
  (ju/display-errors (:errors msg))
  (if (rpc/unauthorized? status)
    (ju/nav-to-login-page)))

;-----------------------------------------------------------------------
; Main screen event handling.
;-----------------------------------------------------------------------
(defaction init-events []
  "#jsk-home-action"         (events/listen :click #(dashboard/show-dashboard))
  "#explorer-action"         (events/listen :click #(explorer/show))
  "#show-dashboard-action"   (events/listen :click #(dashboard/show-dashboard))
  "#execution-list-action"   (events/listen :click #(ju/display-executions))
  "#execution-search-action" (events/listen :click #(search/show-execution-search)))

(defn ws-connect []
  (let [{:keys [in out]} (rpc/ws-connect! (str "ws://" ju/host "/events"))]
    (go
     (loop [[msg-type msg] (<! out)]
       (println "msg-type: " msg-type ", msg: " msg)
       (when (= :message msg-type)
         (when (:execution-event msg)
           (dashboard/process-event msg)
           (executions/add-execution msg)
           (w/event-received msg))
         (when (:crud-event msg)
           (explorer/handle-event msg)
           (dashboard/process-crud-event msg)))
       (recur (<! out))))))

;-----------------------------------------------------------------------
; Initilization
;-----------------------------------------------------------------------
(defn- init []
  (enable-console-print!)
  (println "Begin initializing JSK UI.")
  (init-events)

  (println "Initializing jsplumb.")
  (plumb/init)

  (println "Initializing the explorer.")
  (explorer/init)
  (workflow/init)

  (println "Adding default XHR error handler.")
  (reset! rpc/error-handler rpc-error-handler)

  (println "Begin initializing websocket.")
  (ws-connect)

  (dashboard/show-dashboard)
  (println "Initialization complete.")

  (repl-connect))


;-----------------------------------------------------------------------
; UI begins ticking here.
;-----------------------------------------------------------------------
(set! (.-onload js/window) init)
