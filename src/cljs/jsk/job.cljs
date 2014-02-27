(ns jsk.job
  (:require [jsk.rpc :as rpc]
            [jsk.util :as util]
            [jsk.schedule :as s]
            [jsk.node :as node]
            [jsk.rfn :as rfn]
            [cljs.core.async :as async :refer [<!]]
            [enfocus.core :as ef]
            [enfocus.effects :as effects]
            [enfocus.events :as events])
  (:use [jayq.core :only [$]])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))

(defn- show-element [sel]
  (-> sel $ .show))

(defn- hide-element [sel]
  (-> sel $ .hide))

(defn- show-save-success []
  (show-element "#job-save-success")
  (ef/at "#job-save-success"  (effects/fade-out 1000)))

(defn- save-job [e]
  (go
    (let [form (ef/from "#job-save-form" (ef/read-form))
          data (util/update-str->int form :job-id)
          agent-id (-> form :agent-id first util/update-str->int)
          data1 (assoc data :is-enabled (util/element-checked? "is-enabled"))
          data2 (merge data1 {:max-concurrent 1 :max-retries 1 :agent-id agent-id})
          job-id (<! (rfn/save-job data2))]
      (show-save-success)
      (util/log (str "Form is:__>" form))
      (util/log (str "agent-id is " agent-id))
      (util/log (str "Job saved with id " job-id)))))

(defn- gen-agent-options [agents selected-agent-id]
  (->> agents
    (map (fn [{:keys [agent-id agent-name]}]
           (let [sel-txt (if (= agent-id selected-agent-id) " selected " "")]
             (str "<option value='" agent-id "'" sel-txt ">" agent-name "</option>"))))
    (apply str)))

;-----------------------------------------------------------------------
; Edit Job
;-----------------------------------------------------------------------
(em/defsnippet job-details :compiled "public/templates/job.html" "#job-info" [j agents]
  "#job-id"                  (ef/set-attr :value (str (:job-id j)))
  "#node-directory-id"       (ef/set-attr :value (str (:node-directory-id j)))
  "#job-id-lbl"              (ef/content (str (:job-id j)))
  "#job-name"                (ef/set-attr :value (:job-name j))
  "#job-desc"                (ef/content (:job-desc j))
  "#execution-directory"     (ef/set-attr :value (:execution-directory j))
  "#command-line"            (ef/content (:command-line j))
  "select > option"          (ef/remove-node)
  "#agent-id"                (ef/append (gen-agent-options agents (:agent-id j)))
  "#max-concurrent"          (ef/set-attr :value "1") ; FIXME: hardcoded
  "#max-retries"             (ef/set-attr :value "1") ; FIXME: hardcoded
  "#is-enabled"              (ef/do->
                               (ef/set-prop "checked" (:is-enabled j)
                               (ef/set-attr :value (str (:is-enabled j)))))
  "#save-btn"                (events/listen :click save-job))

(defn show-job-details [job-id]
  (go
    (let [j (<! (rfn/fetch-job-details job-id))
          schedules (<! (rfn/fetch-schedule-associations job-id))
          alerts (<! (rfn/fetch-alert-associations job-id))
          agents (<! (rfn/fetch-all-agents))]
      (util/show-explorer-node (job-details j agents))
      (hide-element "#job-save-success")
      (node/populate-schedule-assoc-list job-id schedules)
      (node/populate-alert-assoc-list job-id alerts))))

