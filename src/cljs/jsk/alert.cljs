(ns jsk.alert
  (:require [jsk.rfn :as rfn]
            [jsk.util :as ju]
            [cljs.core.async :as async :refer [<!]]
            [enfocus.core :as ef]
            [enfocus.effects :as effects]
            [enfocus.events :as events])
  (:use [jayq.core :only [$]])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))

;; ; FIXME: show/hide element repeated in multiple places
;; (defn- show-element [sel]
;;   (-> sel $ .show))

;; (defn- hide-element [sel]
;;   (-> sel $ .hide))

;; (declare save-alert alert-row-clicked)


;; ;-----------------------------------------------------------------------
;; ; List all alerts
;; ;-----------------------------------------------------------------------
;; (em/deftemplate list-alerts :compiled "public/templates/alerts.html" [ss]
;;   ; template has 2 sample rows, so delete all but the first
;;   ; and then apply the clone on the first child
;;   "tbody > :not(tr:first-child)" (ef/remove-node)
;;   "tbody > tr" (em/clone-for [s ss]
;;                  "td.alert-id" #(ef/at (ju/parent-node %1)
;;                                           (ef/do->
;;                                             (ef/set-attr :data-alert-id (str (:alert-id s)))
;;                                             (events/listen :click alert-row-clicked)))
;;                  "td.alert-id" (ef/content (str (:alert-id s)))
;;                  "td.alert-name" (ef/content (:alert-name s))
;;                  "td.alert-desc" (ef/content (:alert-desc s))
;;                  "td.cron-expr" (ef/content (:cron-expression s))
;;                  "td.create-ts" (ef/content (str (:create-ts s)))))

;; ;-----------------------------------------------------------------------
;; ; Edit Alert
;; ;-----------------------------------------------------------------------
;; (em/deftemplate edit-alert :compiled "public/templates/edit-alert.html" [s]
;;   "#alert-id"     (ef/set-attr :value (str (:alert-id s)))
;;   "#alert-id-lbl" (ef/content (str (:alert-id s)))
;;   "#alert-name"   (ef/set-attr :value (:alert-name s))
;;   "#alert-desc"   (ef/content (:alert-desc s))
;;   "#cron-expression" (ef/set-attr :value (:cron-expression s))
;;   "#save-btn"        (events/listen :click save-alert))




;; (defn show-alerts []
;;   (go
;;    (let [ss (<! (rfn/fetch-all-alerts))]
;;      (ju/showcase (list-alerts ss)))))

;; (defn- save-alert [e]
;;   (go
;;     (let [form (ef/from "#alert-save-form" (ef/read-form))
;;           data (ju/update-str->int form :alert-id)
;;           {:keys [success? alert-id errors] :as save-result} (<! (rfn/save-alert data))]
;;       (ju/log (str "Result: " save-result))
;;       (if success?
;;         (show-alerts)
;;         (when errors
;;           (ju/display-errors (-> errors vals flatten))
;;           (edit-alert form))))))

;; (defn- show-alert-edit [s]
;;   (ju/showcase (edit-alert s)))


;; (defn- alert-row-clicked [e]
;;   (go
;;     (let [id (ef/from (ju/event-source e) (ef/get-attr :data-alert-id))
;;           sched (<! (rfn/fetch-alert-details id))]
;;       (show-alert-edit sched))))

;; (defn show-add-alert []
;;   (show-alert-edit {:alert-id -1}))



;; ;-----------------------------------------------------------------------
;; ; Job + Alert associations
;; ;-----------------------------------------------------------------------
;; (defn- hide-save-success []
;;   (hide-element "#assoc-save-success"))

;; (defn- show-save-success []
;;   (show-element "#assoc-save-success")
;;   (ef/at "#assoc-save-success"  (effects/fade-out 1000)))

;; (defn- parse-alert-assoc-form []
;;   (let [form (ef/from "#alert-assoc-form" (ef/read-form))
;;         alert-id-strs (if-let [sch-id (:alert-id form)]
;;                            (ju/ensure-coll sch-id)
;;                            [])
;;         alert-ids (map ju/str->int alert-id-strs)]
;;     {:node-id (-> :node-id form ju/str->int)
;;      :alert-ids alert-ids}))

;; (defn- save-alert-assoc [e]
;;   (go
;;    (let [data (parse-alert-assoc-form)]
;;      (<! (rfn/save-alert-associations data))
;;      (show-save-success))))

;; (em/defsnippet alert-assoc :compiled "public/templates/alert-associations.html" "#alert-associations" [node ss selected-ids]
;;   "#node-id"            (ef/set-attr :value (str (:node-id node)))
;;   "#node-name"          (ef/content (:node-name node))
;;   "#alert-assoc-div" (em/clone-for [s ss]
;;                           "label" (ef/content (:alert-name s))
;;                           "input" (ef/do->
;;                                     (ef/set-attr :value (str (:alert-id s)))
;;                                     (ef/set-prop :checked (contains? selected-ids (:alert-id s)))))
;;   "#save-assoc-btn"     (events/listen :click save-alert-assoc))


;; (defn show-alert-assoc [node-id]
;;   (go
;;     (let [node-info (<! (rfn/fetch-node-info node-id))
;;           ss (<! (rfn/fetch-all-alerts))
;;           assoc-alert-ids (<! (rfn/fetch-alert-associations node-id))]
;;       (ju/showcase (alert-assoc node-info ss assoc-alert-ids))
;;       (hide-save-success))))





