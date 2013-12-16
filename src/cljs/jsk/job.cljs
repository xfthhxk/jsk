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



;-----------------------------------------------------------------------
; Edit Job
;-----------------------------------------------------------------------
(em/deftemplate edit-job :compiled "public/templates/edit-job.html" [j]
  "#job-id"                  (ef/set-attr :value (str (:job-id j)))
  "#job-id-lbl"              (ef/content (str (:job-id j)))
  "#job-name"                (ef/set-attr :value (:job-name j))
  "#job-desc"                (ef/content (:job-desc j))
  "#execution-directory"     (ef/set-attr :value (:execution-directory j))
  "#command-line"            (ef/content (:command-line j))
  "#agent-id"                (ef/set-attr :value "1") ; FIXME: hardcoded
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
          data1 (assoc data :is-enabled (ju/element-checked? "is-enabled"))
          data2 (merge data1 {:max-concurrent 1 :max-retries 1 :agent-id 1})
          job-id (<! (rfn/save-job data2))]
      (ju/log (str "Job saved with id " job-id))
      (show-jobs))))

(defn job-row-clicked [e]
  (go
    (let [id (ef/from (ju/event-source e) (ef/get-attr :data-job-id))
          j (<! (rfn/fetch-job-details id))]
      (ju/showcase (edit-job j)))))


(defn show-add-job []
  (ju/showcase (edit-job {:job-id -1 :is-enabled false})))
