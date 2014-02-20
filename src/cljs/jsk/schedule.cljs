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
(em/deftemplate edit-schedule :compiled "public/templates/edit-schedule.html" [s]
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

;-----------------------------------------------------------------------
; Job + Schedule associations
;-----------------------------------------------------------------------
; FIXME: show/hide element repeated in multiple places
(defn- show-element [sel]
  (-> sel $ .show))

(defn- hide-element [sel]
  (-> sel $ .hide))

(defn- hide-save-success []
  (hide-element "#assoc-save-success"))

(defn- show-save-success []
  (show-element "#assoc-save-success")
  (ef/at "#assoc-save-success"  (effects/fade-out 1000)))

(defn- parse-schedule-assoc-form []
  (let [form (ef/from "#schedule-assoc-form" (ef/read-form))
        schedule-id-strs (if-let [sch-id (:schedule-id form)]
                           (ju/ensure-coll sch-id)
                           [])
        schedule-ids (map ju/str->int schedule-id-strs)]
    {:node-id (-> :node-id form ju/str->int)
     :schedule-ids schedule-ids}))

(defn- save-schedule-assoc [e]
  (go
   (let [data (parse-schedule-assoc-form)]
     (<! (rfn/save-schedule-associations data))
     (show-save-success))))

(em/defsnippet schedule-assoc :compiled "public/templates/schedule-associations.html" "#schedule-associations" [node ss selected-ids]
  "#node-id"            (ef/set-attr :value (str (:node-id node)))
  "#node-name"          (ef/content (:node-name node))
  "#schedule-assoc-div" (em/clone-for [s ss]
                          "label" (ef/content (:schedule-name s))
                          "input" (ef/do->
                                    (ef/set-attr :value (str (:schedule-id s)))
                                    (ef/set-prop :checked (contains? selected-ids (:schedule-id s)))))
  "#save-assoc-btn"     (events/listen :click save-schedule-assoc))


(defn show-schedule-assoc [node-id]
  (go
    (let [node-info (<! (rfn/fetch-node-info node-id))
          ss (<! (rfn/fetch-all-schedules))
          assoc-schedule-ids (<! (rfn/fetch-schedule-associations node-id))]
      (ju/showcase (schedule-assoc node-info ss assoc-schedule-ids))
      (hide-save-success))))





