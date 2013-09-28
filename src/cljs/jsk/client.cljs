(ns jsk.client
    (:require [enfocus.core :as ef]
              [enfocus.effects :as effects]
              [enfocus.events :as events]
              [clojure.browser.repl :as repl]
              [jsk.rpc :as rpc]
              [jsk.schedule :as s])
    (:use-macros [enfocus.macros :only [deftemplate defsnippet defaction]]))

;;************************************************
;; Dev stuff
;;************************************************
(def dev-mode true)

(defn repl-connect []
 (when dev-mode
   (repl/connect "http://localhost:9000/repl")))


(defn my-cb
  ([x]
    (.log js/console "1 arg my-cb")
    (.log js/console x))
  ([x y]
   (.log js/console "2 arg my-cb")))

(defn do-get [e]
  (.log js/console "In do-get!")
  (rpc/GET "/schedules" my-cb))

(defn do-post [e]
  (.log js/console "In do-post!")
  (let [data {:schedule-id "9"  :schedule-name "oh wow mary" :schedule-desc "my new schedule desc upd" :cron-expression "google me"}]
    (rpc/POST "/schedules/save" data my-cb)))

(defaction init []
  "#ajax-get" (events/listen :click do-get)
  "#ajax-post" (events/listen :click do-post)
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
