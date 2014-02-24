(ns jsk.job
  (:require [jsk.rpc :as rpc]
            [jsk.util :as ju]
            [jsk.schedule :as s]
            [jsk.rfn :as rfn]
            [cljs.core.async :as async :refer [<!]]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))

(defn- save-job [e]
  (go
    (let [form (ef/from "#job-save-form" (ef/read-form))
          data (ju/update-str->int form :job-id)
          agent-id (-> form :agent-id first ju/update-str->int)
          data1 (assoc data :is-enabled (ju/element-checked? "is-enabled"))
          data2 (merge data1 {:max-concurrent 1 :max-retries 1 :agent-id agent-id})
          job-id (<! (rfn/save-job data2))]
      (ju/log (str "Form is:__>" form))
      (ju/log (str "agent-id is " agent-id))
      (ju/log (str "Job saved with id " job-id)))))

(defn- gen-agent-options [agents selected-agent-id]
  (->> agents
    (map (fn [{:keys [agent-id agent-name]}]
           (let [sel-txt (if (= agent-id selected-agent-id) " selected " "")]
             (str "<option value='" agent-id "'" sel-txt ">" agent-name "</option>"))))
    (apply str)))

;-----------------------------------------------------------------------
; Edit Job
;-----------------------------------------------------------------------
(em/defsnippet job-details :compiled "public/templates/job.html" "#job-info" [j schedules agents]
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
  "#node-schedules-list > :not(li:first-child)" (ef/remove-node)
  "#node-schedules-list > li" (em/clone-for [{:keys [schedule-name schedule-id node-schedule-id]} schedules]
                                  "label" #(ef/at (ju/parent-node %1)
                                                  (ef/set-attr :id (str "node-schedule-" node-schedule-id)))
                                  "label" (ef/content schedule-name))
  "#save-btn"                (events/listen :click save-job))


(defn show-job-details [job-id]
  (go
    (let [j (<! (rfn/fetch-job-details job-id))
          schedules (<! (rfn/fetch-schedule-associations job-id))
          agents (<! (rfn/fetch-all-agents))]
      (ju/show-explorer-node (job-details j schedules agents)))))
