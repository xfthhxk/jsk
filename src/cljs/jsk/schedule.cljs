(ns jsk.schedule
  (:require [jsk.rpc :as rpc]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:require-macros [enfocus.macros :as em]))

(declare save-schedule schedule-row-clicked list-schedules edit-schedule show-schedule show-schedules)

(defn by-id [id]
  (.getElementById js/document id))

(defn log [x]
  (.log js/console x))

; extract the element that raised the event
(defn event-source [e]
  (.-currentTarget e))

(defn- save-schedule [e]
  (log (str "in save-schedule: " e))
  (let [sched-map (ef/from "#schedule-save-form" (ef/read-form))]
    (log (pr-str sched-map))
    (rpc/POST "/schedules/save" sched-map #(show-schedules))))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Edit Schedule
;;;;;;;;;;;;;;;;;;;;;;;;;;
(em/deftemplate edit-schedule :compiled "public/templates/edit-schedule.html" [s]
  "#schedule-id" (ef/set-attr :value (str (:schedule-id s)))
  "#schedule-id-lbl" (ef/content (str (:schedule-id s)))
  "#schedule-name" (ef/set-attr :value (:schedule-name s))
  "#schedule-desc" (ef/content (:schedule-desc s))
  "#cron-expression" (ef/set-attr :value (:cron-expression s))
  "#save-btn" (events/listen :click save-schedule))

(defn- show-schedule [s]
  (log (str s))
  (ef/at "#container" (ef/content (edit-schedule (first s)))))


(defn- schedule-row-clicked [e]
  (let [id (ef/from (event-source e) (ef/get-attr :data-schedule-id))]
    (rpc/GET (str "/schedules/" id) show-schedule)))


; template has 2 sample rows, so delete all but the first
; and then apply the clone on the first child
(em/deftemplate list-schedules :compiled "public/templates/schedules.html" [ss]
  "tbody > :not(tr:first-child)" (ef/remove-node)
  "tbody > tr" (em/clone-for [s ss]
                 "td.schedule-id" #(ef/at (.-parentNode %1)
                                          (ef/do->
                                            (ef/set-attr :data-schedule-id (str (:schedule-id s)))
                                            (events/listen :click schedule-row-clicked)))
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
  (ef/at "#container" (ef/content (list-schedules ss))))


(defn show-schedules []
  (log "Show schedules called")
  (rpc/GET "/schedules" display-schedules))

(defn show-add-schedule []
  (log "Add schedule called")
  (ef/at "#container" (ef/content (edit-schedule {:schedule-id -1}))))
