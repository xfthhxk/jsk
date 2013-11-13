(ns jsk.client
    (:require [enfocus.core :as ef]
              [enfocus.effects :as effects]
              [enfocus.events :as events]
              [clojure.browser.repl :as repl]
              [cljs.core.async :as async :refer [<!]]
              [jsk.plumb :as plumb]
              [jsk.rpc :as rpc]
              [jsk.util :as ju]
              [jsk.job :as j]
              [jsk.executions :as executions]
              [jsk.workflow :as w]
              [jsk.schedule :as s])
    (:require-macros [cljs.core.async.macros :refer [go]])
    (:use-macros [enfocus.macros :only [deftemplate defsnippet defaction]]))

;-----------------------------------------------------------------------
; Dev stuff
;-----------------------------------------------------------------------
(def dev-mode true)

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
  "#show-dashboard-action" (events/listen :click #(ju/display-dashboard))
  "#job-list-action"       (events/listen :click #(j/show-jobs))
  "#job-add-action"        (events/listen :click #(j/show-add-job))
  "#schedule-list-action"  (events/listen :click #(s/show-schedules))
  "#schedule-add-action"   (events/listen :click #(s/show-add-schedule))
  "#workflow-list-action"  (events/listen :click #(w/show-workflows))
  "#workflow-add-action"   (events/listen :click #(w/show-designer))
  "#workflow-test-action"  (events/listen :click #(w/show-test)))


(defn ws-connect []
  (let [{:keys [in out]} (rpc/ws-connect! (str "ws://" ju/host "/executions"))]
    (go
     (loop [[msg-type msg] (<! out)]
       (ju/log (str "msg-type: " msg-type ", msg: " msg))
       (when (= :message msg-type)
         (executions/add-execution msg))
       (recur (<! out))))))

;-----------------------------------------------------------------------
; Initilization
;-----------------------------------------------------------------------
(defn- init []
  (ju/log "Begin initializing JSK UI.")
  (init-events)

  (ju/log "Initializing jsplumb.")
  (plumb/init)

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
