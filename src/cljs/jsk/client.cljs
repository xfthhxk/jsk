(ns jsk.client
    (:require [enfocus.core :as ef]
              [enfocus.effects :as effects]
              [enfocus.events :as events]
              [clojure.browser.repl :as repl]
              [jsk.schedule :as s])
    (:use-macros [enfocus.macros :only [deftemplate defsnippet defaction]]))

;;************************************************
;; Dev stuff
;;************************************************
(def dev-mode true)

(defn repl-connect []
 (when dev-mode
   (repl/connect "http://localhost:9000/repl")))

(defaction init []
  "#schedule-list-action" (events/listen :click #(s/show-schedules))
  "#schedule-add-action"  (events/listen :click #(s/show-add-schedule)))

;;************************************************
;; onload
;;************************************************

(set! (.-onload js/window)
      #(do
         (.log js/console "OnLoad called!")
         (init)
         (repl-connect)))
