(ns jsk.schedule
  (:require [jsk.rfn :as rfn]
            [jsk.util :as ju]
            [cljs.core.async :as async :refer [<!]]
            [enfocus.core :as ef]
            [enfocus.effects :as effects]
            [enfocus.events :as events])
  (:use [jayq.core :only [$]])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))


(defn- save-schedule [e]
  (go
    (let [form (ef/from "#schedule-save-form" (ef/read-form))
          data (ju/update-str->int form :schedule-id)
          {:keys [success? schedule-id errors] :as save-result} (<! (rfn/save-schedule data))]
      (ju/log (str "Result: " save-result))
      (when (seq errors) 
        (ju/display-errors (-> errors vals flatten))))))

;-----------------------------------------------------------------------
; Edit Schedule
;-----------------------------------------------------------------------
(em/defsnippet edit-schedule :compiled "public/templates/schedule.html" "#schedule-save-form-div" [s]
  "#schedule-id"     (ef/set-attr :value (str (:schedule-id s)))
  "#schedule-id-lbl" (ef/content (str (:schedule-id s)))
  "#schedule-name"   (ef/set-attr :value (:schedule-name s))
  "#schedule-desc"   (ef/content (:schedule-desc s))
  "#cron-expression" (ef/set-attr :value (:cron-expression s))
  "#save-btn"        (events/listen :click save-schedule))


(defn- show-schedule-details [schedule-id]
  (go
    (let [sched (<! (rfn/fetch-schedule-details schedule-id))]
      (ju/show-explorer-node (edit-schedule sched)))))


(em/defsnippet list-node-schedule-assocs :compiled "public/templates/schedule.html" "#node-schedule-assoc-div" [schedule-assocs]
  "#node-schedules-list > :not(li:first-child)" (ef/remove-node)
  "#node-schedules-list > li" (em/clone-for [{:keys [schedule-name schedule-id node-schedule-id]} schedules]
                                  "label" #(ef/at (ju/parent-node %1)
                                                  (ef/set-attr :id (str "node-schedule-" node-schedule-id)))
                                  "label" (ef/content schedule-name)
                                  "button.close" (events/listen :click #(rfn/rm-schedule-assoc node-schedule-id))))


