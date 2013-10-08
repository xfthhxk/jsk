(ns jsk.job
  (:require [jsk.rpc :as rpc]
            [jsk.util :as ju]
            [cljs.core.async :as async :refer [<!]]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))

(declare save-job job-row-clicked save-job-schedule-assoc)

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
  "#execution-directory"     (ef/set-attr :value (:execution-directory j))
  "#command-line"            (ef/content (:command-line j))
  "#is-enabled"              (ef/do->
                               (ef/set-prop "checked" (:is-enabled j)
                               (ef/set-attr :value (str (:is-enabled j)))))
  "#save-btn"            (events/listen :click save-job))


;-----------------------------------------------------------------------
; Job + Schedule associations
;-----------------------------------------------------------------------
(em/deftemplate schedule-assoc :compiled "public/templates/job-schedule-assoc.html" [job ss selected-ids]
  "#job-id" (ef/set-attr :value (str (:job-id job)))
  "#schedule-assoc-div" (em/clone-for [s ss]
                          "label" (ef/content (:schedule-name s))
                          "input" (ef/do->
                                    (ef/set-attr :value (str (:schedule-id s)))
                                    (ef/set-prop :checked (contains? selected-ids (:schedule-id s)))))
  "#save-assoc-btn"     (events/listen :click save-job-schedule-assoc))


(defn show-jobs []
  (go
   (let [jj (<! (rpc/GET "/jobs"))]
   (ef/at "#container" (ef/content (list-jobs jj))))))

(defn- save-job [e]
  (go
    (let [form (ef/from "#job-save-form" (ef/read-form))
          data (ju/update-str->int form :job-id)
          data1 (assoc data :is-enabled (ju/element-checked? "is-enabled"))
          job-id (<! (rpc/POST "/jobs/save" data1))]
      (ju/log (str "Job saved with id " job-id))
      (show-jobs))))

(defn show-job-edit [j ss associated-schedule-ids]
  (ef/at "#container" (ef/content (edit-job j)))
  (if ss
    (ef/at "#job-schedule-associations" (ef/content (schedule-assoc j ss associated-schedule-ids)))))

(defn job-row-clicked [e]
  (go
    (let [id (ef/from (ju/event-source e) (ef/get-attr :data-job-id))
          url (str "/jobs/" id)
          j (<! (rpc/GET url))
          ss (<! (rpc/GET "/schedules"))
          assoc-schedule-ids (<! (rpc/GET (str url "/sched-assoc")))]
      (show-job-edit j ss assoc-schedule-ids))))


(defn show-add-job []
  (show-job-edit {:job-id -1 :is-enabled false} nil nil))


(defn- parse-job-schedule-assoc-form []
  (let [form (ef/from "#schedule-assoc-form" (ef/read-form))
        schedule-id-strs (ju/ensure-coll (:schedule-id form))
        schedule-ids (map ju/str->int schedule-id-strs)]
    (ju/log (str "form is: " form))
    (ju/log (str "schedule-id-strs is: " schedule-id-strs))
    (ju/log (str "schedule-ids is: " schedule-ids))
    {:job-id (-> :job-id form ju/str->int)
     :schedule-ids schedule-ids}))


(defn- save-job-schedule-assoc [e]
  (go
   (let [data (parse-job-schedule-assoc-form)]
     (<! (rpc/POST "/jobs/assoc" data))
     (ef/at "#save-assoc-msg-label" (ef/content "Associations saved.")))))























