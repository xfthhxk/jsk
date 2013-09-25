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



(defaction sfn []
  (.log js/console "SFN called!")
  (s/show-schedules))

(defaction init []
  "#btn" (events/listen :click sfn))

;;************************************************
;; onload
;;************************************************

(set! (.-onload js/window)
      #(do
         (.log js/console "OnLoad called!")
         (init)
         (repl-connect)))
