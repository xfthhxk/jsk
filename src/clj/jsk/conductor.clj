(ns jsk.conductor
  "Coordination of workflows."
  (:require
            [jsk.quartz :as q]
            [jsk.workflow :as w]
            [jsk.db :as db]
            [clojurewerkz.quartzite.scheduler :as qs]
            [clojure.string :as str]
            [clojure.core.async :refer [chan go go-loop put! >! <!]]
            [taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal)])
  (:import (org.quartz JobDetail JobExecutionContext JobKey Scheduler)))

(def exec-tbl (atom {}))

(def cond-chan (atom nil))

(defn- execute-job [^JobKey job-key]
  (.triggerJob ^Scheduler @qs/*scheduler* job-key))

(defn- run-job [job-id]
  (-> job-id q/make-job-key execute-job))


;-----------------------------------------------------------------------
; Input map is something like:
;
; {:roots #{2}, :table {2 {true #{1 3}}}}
;
; This is a workflow made of 3 jobs and terminates
; with the execution of 1 and 3.
;-----------------------------------------------------------------------
(defn- run-wf [wf-id]
  (try
    (let [{:keys[execution-id roots table] :as m} (w/setup-execution wf-id)]
      (info "execution id: " execution-id)
      (info "roots: " roots)
      (info "table: " table)
      (swap! exec-tbl assoc execution-id m))
    (catch Exception e
      (error e))))

;-----------------------------------------------------------------------
; Schedule job to be triggered now.
;-----------------------------------------------------------------------
(defn trigger-job-now [job-id]
  (put! @cond-chan {:event :trigger-job :job-id job-id})
  true)

;-----------------------------------------------------------------------
; Schedule workflow to be triggered now.
;-----------------------------------------------------------------------
(defn trigger-workflow-now
  [wf-id]
  (info "wf-id: " wf-id ", cond-chan: " cond-chan)
  (put! @cond-chan {:event :trigger-wf :wf-id wf-id})
  true)


(defmulti dispatch :event)

(defmethod dispatch :trigger-job [msg]
  (let [{:keys [job-id]} msg]
    (run-job job-id)))


(defmethod dispatch :trigger-wf [msg]
  (let [{:keys [wf-id]} msg]
    (run-wf wf-id)))


(defn init
  "Sets up a queue which other parts of the program can write
   messages to. A message is a map with at least an :event key.
   Rest of the keys will depend on the event and what's relevant."
  [ch]
  (reset! cond-chan ch)
  (go-loop [msg (<! ch)]
     (dispatch msg)
     (recur (<! ch))))










