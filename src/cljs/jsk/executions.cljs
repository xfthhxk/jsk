(ns jsk.executions
  (:require [jsk.rpc :as rpc]
            [jsk.util :as ju]
            [cljs.core.async :as async :refer [<!]]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))


(defn- gen-finish-id [exec-id]
  (str "job-execution-finish-" exec-id))

(defn- gen-status-id [exec-id]
  (str "job-execution-status-" exec-id))


;-----------------------------------------------------------------------
; Make an execution row
;-----------------------------------------------------------------------
(em/deftemplate make-execution-row-1 :compiled "public/templates/execution.html" [e]
  "tr" (ef/do->
          (ef/set-attr :data-execution-id (str (:job-execution-id e)))
          (ef/set-attr :data-job-id (str (:job-id e))))
  "td.job-execution-id" (ef/content (str (:job-execution-id e)))
  "td.job-name" (ef/content (:job-name e))
  "td.job-start" (ef/content (str (:job-start e)))
  "td.job-finish" (ef/do->
                   (ef/content "")     ; not finished yet
                   (ef/set-attr :id (gen-finish-id (:job-execution-id e))))
  "td.job-status" (ef/do->
                   (ef/content "Running")
                   (ef/set-attr :id (gen-status-id (:job-execution-id e)))))

(em/deftemplate make-execution-row :compiled "public/templates/execution.html" [e]
  "li" (ef/content (str e)))


; make-execution-row and make it the first row
(defn add-execution [e]
  (ju/log "In add-execution arggh")
  (ju/log (str "Got execution msg: " e))
  (ef/at "#exec-list"
         (ef/append (make-execution-row e))))






