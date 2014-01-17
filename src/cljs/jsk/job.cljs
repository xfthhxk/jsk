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

(declare save-job job-row-clicked)

(defn- trigger-job-now [e]
  ; the row the button is in is also a listener for clicks,
  ; we're handling it here
  (.stopPropagation e)
  (let [source (ju/event-source e)
        job-id (ef/from source (ef/get-attr :data-job-id))]
    (rfn/trigger-job-now job-id)))



;-----------------------------------------------------------------------
; List all jobs
;-----------------------------------------------------------------------
(em/deftemplate list-jobs :compiled "public/templates/jobs.html" [jj]
  ; template has 2 sample rows, so delete all but the first
  ; and then apply the clone on the first child
  "tbody > :not(tr:first-child)" (ef/remove-node)
  "tbody > tr" (em/clone-for [j jj]
                 "td.job-id" #(ef/at (ju/parent-node %1)
                                          (ef/do->
                                            (ef/set-attr :data-job-id (str (:job-id j)))
                                            (events/listen :click job-row-clicked)))
                 "td.job-id" (ef/content (str (:job-id j)))
                 "td.job-name" (ef/content (:job-name j))
                 "td.job-is-enabled" (ef/content (str (:is-enabled j)))
                 "td.job-trigger-now > button" (ef/do->
                                                 (ef/set-attr :data-job-id (str (:job-id j)))
                                                 (events/listen :click trigger-job-now))))


(defn- gen-agent-options [agents selected-agent-id]
  (->> agents
    (map (fn [{:keys [agent-id agent-name]}]
           (let [sel-txt (if (= agent-id selected-agent-id) " selected " "")]
             (str "<option value='" agent-id "'" sel-txt ">" agent-name "</option>"))))
    (apply str)))


;-----------------------------------------------------------------------
; Edit Job
;-----------------------------------------------------------------------
(em/deftemplate edit-job :compiled "public/templates/edit-job.html" [j agents]
  "#job-id"                  (ef/set-attr :value (str (:job-id j)))
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
  "#save-btn"            (events/listen :click save-job)
  "#view-assoc-schedules"    (if (= -1 (:job-id j))
                               (ef/remove-node)
                               (events/listen :click #(s/show-schedule-assoc (:job-id j)))))

(defn show-jobs []
  (go
   (let [jj (<! (rfn/fetch-all-jobs))]
     (ju/showcase (list-jobs jj)))))

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
      (ju/log (str "Job saved with id " job-id))
      (show-jobs))))

(defn job-row-clicked [e]
  (go
    (let [id (ef/from (ju/event-source e) (ef/get-attr :data-job-id))
          j (<! (rfn/fetch-job-details id))
          agents (<! (rfn/fetch-all-agents))]
      (ju/showcase (edit-job j agents)))))


(defn show-add-job []
  (go
    (let [agents (<! (rfn/fetch-all-agents))]
      (ju/showcase (edit-job {:job-id -1 :is-enabled false} agents)))))

