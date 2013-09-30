(ns jsk.schedule
  (:require [jsk.rpc :as rpc]
            [jsk.util :as ju]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:require-macros [enfocus.macros :as em]))


(declare save-schedule schedule-row-clicked)

;-----------------------------------------------------------------------
; List all schedules
;-----------------------------------------------------------------------
(em/deftemplate list-schedules :compiled "public/templates/schedules.html" [ss]
  ; template has 2 sample rows, so delete all but the first
  ; and then apply the clone on the first child
  "tbody > :not(tr:first-child)" (ef/remove-node)
  "tbody > tr" (em/clone-for [s ss]
                 "td.schedule-id" #(ef/at (ju/parent-node %1)
                                          (ef/do->
                                            (ef/set-attr :data-schedule-id (str (:schedule-id s)))
                                            (events/listen :click schedule-row-clicked)))
                 "td.schedule-id" (ef/content (str (:schedule-id s)))
                 "td.schedule-name" (ef/content (:schedule-name s))
                 "td.schedule-desc" (ef/content (:schedule-desc s))
                 "td.cron-expr" (ef/content (:cron-expression s))
                 "td.created-at" (ef/content (str (:created-at s)))))

;-----------------------------------------------------------------------
; Edit Schedule
;-----------------------------------------------------------------------
(em/deftemplate edit-schedule :compiled "public/templates/edit-schedule.html" [s]
  "#schedule-id"     (ef/set-attr :value (str (:schedule-id s)))
  "#schedule-id-lbl" (ef/content (str (:schedule-id s)))
  "#schedule-name"   (ef/set-attr :value (:schedule-name s))
  "#schedule-desc"   (ef/content (:schedule-desc s))
  "#cron-expression" (ef/set-attr :value (:cron-expression s))
  "#save-btn"        (events/listen :click save-schedule))


(defn- display-schedules [ss]
  (ef/at "#container" (ef/content (list-schedules ss))))

(defn show-schedules []
  (rpc/GET "/schedules" display-schedules))

(defn- save-schedule [e]
  (let [form (ef/from "#schedule-save-form" (ef/read-form))
        data (ju/update-str->int form :schedule-id)]
    (rpc/POST "/schedules/save" data #(show-schedules))))

(defn- show-schedule-edit [s]
  (ef/at "#container" (ef/content (edit-schedule (first s)))))

(defn- schedule-row-clicked [e]
  (let [id (ef/from (ju/event-source e) (ef/get-attr :data-schedule-id))]
    (rpc/GET (str "/schedules/" id) show-schedule-edit)))

(defn show-add-schedule []
  (ef/at "#container" (ef/content (edit-schedule {:schedule-id -1}))))
