(ns jsk.schedule
  (:require [ajax.core :refer [GET POST]]
            [enfocus.core :as ef])
  (:require-macros [enfocus.macros :as em]))


(defn by-id [id]
  (.getElementById js/document id))

(defn log [x]
  (.log js/console x))

(em/deftemplate edit-schedule-template :compiled "public/templates/edit-schedule.html"
  [{:keys [schedule-name schedule-desc cron-expr errors]}]
  "#schedule-name" (ef/set-attr :value schedule-name)
  "#schedule-desc" (ef/set-attr :value schedule-desc)
  "#cron-expr" (ef/set-attr :value cron-expr))

; template has 2 sample rows, so delete all but the first
; and then apply the clone on the first child
(em/deftemplate list-schedules :compiled "public/templates/schedules.html" [ss]
  "tbody > :not(tr:first-child)" (ef/remove-node)
  "tbody > tr:first-child" (em/clone-for [s ss]
                             "td.schedule-id" (ef/content (str (:schedule-id s)))
                             "td.schedule-name" (ef/content (:schedule-name s))
                             "td.schedule-desc" (ef/content (:schedule-desc s))
                             "td.cron-expr" (ef/content (:cron-expression s))
                             "td.created-at" (ef/content (str (:created-at s)))))


; can't have any log statements etc using the macro
; use ef/at
;(em/defaction display-schedules [ss]
;  "#schedules" (ef/content (str ss)))

(defn- display-schedules [ss]
  (log (str ss))
  (ef/at "#schedules" (ef/content (list-schedules ss))))

(defn- error-handler [{:keys [status status-text]}]
  (log (str "error: " status  " " status-text)))


(defn show-schedules []
  (log "Show schedules called")
  (GET "/schedules" {:handler display-schedules :error-handler error-handler}))
