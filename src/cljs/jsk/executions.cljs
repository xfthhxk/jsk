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

(defn- gen-executing-row-id [execution-id]
  (str "currently-executing-job-execution-id-" execution-id))



;(em/deftemplate make-execution-row :compiled "public/templates/execution.html" [e]
;  "li" (ef/content (str e)))

(em/defsnippet make-execution-row-alt :compiled "public/templates/execution.html" "#execution-list-item" [e]
  "li" (ef/content (str e)))


; make-execution-row and make it the first row
(defn add-execution-alt [e]
  (ju/log "In add-execution arggh")
  (ju/log (str "Got execution msg: " e))
  (ef/at "#exec-list"
         (ef/prepend (make-execution-row-alt e))))

;-----------------------------------------------------------------------
; Make an execution row
;-----------------------------------------------------------------------
(em/defsnippet make-currently-executing-row :compiled "public/templates/execution.html"  "#currently-executing-row"  [e]
  "tr" (ef/do->
          (ef/set-attr :id (gen-executing-row-id (:execution-id e)))
          (ef/set-attr :data-execution-id (str (:execution-id e)))
          (ef/set-attr :data-job-id (str (:job-id e))))
  "td.job-execution-id" (ef/content (str (:execution-id e)))
  "td.job-name" (ef/content (:job-name e))
  "td.job-start" (ef/content (str (:start-ts e))))

(em/defsnippet make-execution-successful-row :compiled "public/templates/execution.html"  "#execution-successful-row"  [e]
  "tr" (ef/do->
          (ef/set-attr :data-execution-id (str (:execution-id e)))
          (ef/set-attr :data-job-id (str (:job-id e))))
  "td.job-execution-id" (ef/content (str (:execution-id e)))
  "td.job-name"         (ef/content (:job-name e))
  "td.job-start"        (ef/content (str (:start-ts e)))
  "td.job-finish"       (ef/content (str (:finish-ts e))))

(em/defsnippet make-execution-error-row :compiled "public/templates/execution.html"  "#execution-error-row"  [e]
  "tr" (ef/do->
          (ef/set-attr :data-execution-id (str (:execution-id e)))
          (ef/set-attr :data-job-id (str (:job-id e))))
  "td.job-execution-id" (ef/content (str (:execution-id e)))
  "td.job-name"         (ef/content (:job-name e))
  "td.job-start"        (ef/content (str (:start-ts e)))
  "td.job-finish"       (ef/content (str (:finish-ts e)))
  "td.job-error-message" (ef/content (str (:error e))))

(defn- execution-started [e]
  (ef/at "#jobs-currently-executing-tbody" (ef/prepend (make-currently-executing-row e))))

(defn- remove-executing-row
  "Remove the row from the currently executing section."
  [execution-id]
  (let [row-selector (str "#" (gen-executing-row-id execution-id))]
    (ef/at row-selector (ef/remove-node))))

(def execution-finished-map {true  {:finish-fn make-execution-successful-row
                                    :ui-id "#jobs-executed-tbody"}
                             false {:finish-fn make-execution-error-row
                                    :ui-id "#jobs-errored-tbody"}})

(defn- execution-finished [{:keys [execution-id success?] :as e}]
  (let [{:keys [finish-fn ui-id]} (execution-finished-map success?)]
    (ef/at ui-id (ef/prepend (finish-fn e)))
    (remove-executing-row execution-id)))


(defn add-execution [e]
  (case (:event e)
    :start (execution-started e)
    :finish (execution-finished e)
    (ju/log (str "An unexpected event value was encountered while adding execution: " e))))





