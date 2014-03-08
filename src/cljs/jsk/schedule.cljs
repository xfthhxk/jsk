(ns jsk.schedule
  (:require [jsk.rfn :as rfn]
            [jsk.util :as util]
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
          data (util/update-str->int form :schedule-id)
          {:keys [success? schedule-id errors] :as save-result} (<! (rfn/save-schedule data))]
      (println "Result: " save-result)
      (when (seq errors) 
        (util/display-errors (-> errors vals flatten))))))

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
      (util/show-explorer-node (edit-schedule sched)))))




