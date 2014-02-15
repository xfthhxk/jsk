(ns jsk.client
    (:require [enfocus.core :as ef]
              [enfocus.effects :as effects]
              [enfocus.events :as events]
              [clojure.browser.repl :as repl]
              [cljs.core.async :as async :refer [<!]]
              [jsk.plumb :as plumb]
              [jsk.explorer :as explorer]
              [jsk.rpc :as rpc]
              [jsk.util :as ju]
              [jsk.job :as j]
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
  (ju/log (str "ERROR status: " status ", msg: " msg))
  (ju/display-errors (:errors msg))
  (if (rpc/unauthorized? status)
    (ju/nav-to-login-page)))

;-----------------------------------------------------------------------
; Main screen event handling.
;-----------------------------------------------------------------------
(defaction init-events []
  "#jsk-home-action"         (events/listen :click #(ju/display-dashboard))
  "#explorer-action"         (events/listen :click #(explorer/show))
  "#show-dashboard-action"   (events/listen :click #(ju/display-dashboard))
  "#execution-search-action" (events/listen :click #(search/show-execution-search))
  "#agent-list-action"       (events/listen :click #(agent/show-agents))
  "#agent-add-action"        (events/listen :click #(agent/show-add-agent))
  "#job-list-action"         (events/listen :click #(j/show-jobs))
  "#job-add-action"          (events/listen :click #(j/show-add-job))
  "#schedule-list-action"    (events/listen :click #(s/show-schedules))
  "#schedule-add-action"     (events/listen :click #(s/show-add-schedule)))


(defn ws-connect []
  (let [{:keys [in out]} (rpc/ws-connect! (str "ws://" ju/host "/events"))]
    (go
     (loop [[msg-type msg] (<! out)]
       (ju/log (str "msg-type: " msg-type ", msg: " msg))
       (when (= :message msg-type)
         (when (:execution-event msg)
           (executions/add-execution msg)
           (w/event-received msg))
         (when (:crud-event msg)
           (explorer/handle-event msg)))
       (recur (<! out))))))

;-----------------------------------------------------------------------
; Initilization
;-----------------------------------------------------------------------
(defn- init []
  (ju/log "Begin initializing JSK UI.")
  (init-events)

  (ju/log "Initializing jsplumb.")
  (plumb/init)

  (ju/log "Initializing the explorer.")
  (explorer/init)

  (ju/log "Adding default XHR error handler.")
  (reset! rpc/error-handler rpc-error-handler)

  (ju/log "Begin initializing websocket.")
  (ws-connect)

  (ju/display-dashboard)
  (ju/log "Initialization complete.")

  (repl-connect))


;-----------------------------------------------------------------------
; UI begins ticking here.
;-----------------------------------------------------------------------
(set! (.-onload js/window) init)
