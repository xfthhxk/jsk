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

(defn- save-alert [e]
  (go
    (let [form (ef/from "#alert-save-form" (ef/read-form))
          data (util/update-str->int form :alert-id)
          data1 (merge data {:is-for-error (util/element-checked? "is-for-error")})
          {:keys [success? alert-id errors] :as save-result} (<! (rfn/save-alert data1))]
      (println "Result: " save-result)
      (when (seq errors)
        (util/display-errors (-> errors vals flatten))))))


;-----------------------------------------------------------------------
; Edit Alert
;-----------------------------------------------------------------------
(em/deftemplate edit-alert :compiled "public/templates/alerts.html" [{:keys [alert-id alert-name alert-desc recipients subject body is-for-error]}]
  "#alert-id"     (ef/set-attr :value (str alert-id))
  "#alert-id-lbl" (ef/content (str alert-id ))
  "#alert-name"   (ef/set-attr :value alert-name)
  "#alert-desc"   (ef/content alert-desc)
  "#recipients"   (ef/set-attr :value recipients)
  "#subject"      (ef/set-attr :value subject)
  "#body"         (ef/content body)
  "#is-for-error" (ef/do->
                   (ef/set-prop "checked" is-for-error)
                   (ef/set-attr :value (str is-for-error)))
  "#save-btn"     (events/listen :click save-alert))

(defn show-alert-details [alert-id]
  (go
   (let [alert-data (<! (rfn/fetch-alert-details alert-id))]
     (util/show-explorer-node (edit-alert alert-data)))))
