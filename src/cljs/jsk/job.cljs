(ns jsk.job
  (:require [jsk.rpc :as rpc]
            [jsk.util :as ju]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:require-macros [enfocus.macros :as em]))


(declare save-job job-row-clicked)

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
                 "td.job-is-enabled" (ef/content (str (:is-enabled j)))))


;-----------------------------------------------------------------------
; Edit Job
;-----------------------------------------------------------------------
(em/deftemplate edit-job :compiled "public/templates/edit-job.html" [j]
  "#job-id"                  (ef/set-attr :value (str (:job-id j)))
  "#job-id-lbl"              (ef/content (str (:job-id j)))
  "#job-name"                (ef/set-attr :value (:job-name j))
  "#job-desc"                (ef/content (:job-desc j))
  "#job-execution-directory" (ef/set-attr :value (:job-execution-directory j))
  "#job-command-line"        (ef/content (:job-command-line j))
  "#is-enabled"              (ef/do->
                               (ef/set-prop "checked" (:is-enabled j)
                               (ef/set-attr :value (str (:is-enabled j)))))
  "#save-btn"                (events/listen :click save-job))

(defn- display-jobs [jj]
  (ef/at "#container" (ef/content (list-jobs jj))))

(defn show-jobs []
  (rpc/GET "/jobs" display-jobs))

(defn- save-job [e]
  (let [form (ef/from "#job-save-form" (ef/read-form))
        data (ju/update-str->int form :job-id)
        data1 (assoc data :is-enabled (ju/element-checked? "is-enabled"))]
    (rpc/POST "/jobs/save" data1 #(show-jobs))))

(defn- show-job-edit [s]
  (ef/at "#container" (ef/content (edit-job (first s)))))

(defn- job-row-clicked [e]
  (let [id (ef/from (ju/event-source e) (ef/get-attr :data-job-id))]
    (rpc/GET (str "/jobs/" id) show-job-edit)))

(defn show-add-job []
  (ef/at "#container" (ef/content (edit-job {:job-id -1 :is-enabled false}))))
