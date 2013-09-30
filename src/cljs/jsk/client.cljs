(ns jsk.client
    (:require [enfocus.core :as ef]
              [enfocus.effects :as effects]
              [enfocus.events :as events]
              [clojure.browser.repl :as repl]
              [jsk.rpc :as rpc]
              [jsk.util :as ju]
              [jsk.schedule :as s])
    (:use-macros [enfocus.macros :only [deftemplate defsnippet defaction]]))

;;************************************************
;; Dev stuff
;;************************************************
(def dev-mode true)

(defn repl-connect []
 (when dev-mode
   (repl/connect "http://localhost:9000/repl")))

(defn rpc-error-handler [status msg]
  (ju/log (str "ERROR status: " status ", msg: " msg))
  (ef/at "#error-div" (ef/content msg)))

(defaction init []
  "#schedule-list-action" (events/listen :click #(s/show-schedules))
  "#schedule-add-action"  (events/listen :click #(s/show-add-schedule)))

;;************************************************
;; onload
;;************************************************

(set! (.-onload js/window)
      #(do
         (ju/log "Begin initializing JSK UI.")
         (init)
         (ju/log "Add default XHR error handler.")
         (reset! rpc/error-handler rpc-error-handler)
         (ju/log "Initialization complete.")
         (repl-connect)))
