(ns jsk.schedule
  (:require [jsk.rfn :as rfn]
            [jsk.util :as ju]
            [cljs.core.async :as async :refer [<!]]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))


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
                 "td.create-ts" (ef/content (str (:create-ts s)))))

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


(defn show-schedules []
  (go
   (let [ss (<! (rfn/fetch-all-schedules))]
     (ju/showcase (list-schedules ss)))))

(defn- save-schedule [e]
  (go
    (let [form (ef/from "#schedule-save-form" (ef/read-form))
          data (ju/update-str->int form :schedule-id)
          {:keys [success? schedule-id errors] :as save-result} (<! (rfn/save-schedule data))]
      (ju/log (str "Result: " save-result))
      (if success?
        (show-schedules)
        (when errors
          (ju/display-errors (-> errors vals flatten))
          (edit-schedule form))))))

(defn- show-schedule-edit [s]
  (ju/showcase (edit-schedule s)))


(defn- schedule-row-clicked [e]
  (go
    (let [id (ef/from (ju/event-source e) (ef/get-attr :data-schedule-id))
          sched (<! (rfn/fetch-schedule-details id))]
      (show-schedule-edit sched))))

(defn show-add-schedule []
  (show-schedule-edit {:schedule-id -1}))
