(ns jsk.alert
  (:require [jsk.rfn :as rfn]
            [jsk.util :as util]
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

(defn- save-alert [e]
  (go
    (let [form (ef/from "#alert-save-form" (ef/read-form))
          data (util/update-str->int form :alert-id)
          {:keys [success? alert-id errors] :as save-result} (<! (rfn/save-alert data))]
      (util/log (str "Result: " save-result))
      (when (seq errors)
        (util/display-errors (-> errors vals flatten))))))



;-----------------------------------------------------------------------
; Edit Alert
;-----------------------------------------------------------------------
(em/deftemplate edit-alert :compiled "public/templates/alerts.html" [{:keys [alert-id alert-name alert-desc subject body]}]
  "#alert-id"     (ef/set-attr :value (str alert-id))
  "#alert-id-lbl" (ef/content (str alert-id ))
  "#alert-name"   (ef/set-attr :value alert-name)
  "#alert-desc"   (ef/content alert-desc)
  "#subject"      (ef/set-attr :value subject)
  "#body"         (ef/content body)
  "#save-btn"     (events/listen :click save-alert))


(defn show-alert-details [alert-id]
  (go
   (let [alert-data (<! (rfn/fetch-alert-details alert-id))]
     (util/show-explorer-node (edit-alert alert-data)))))


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





