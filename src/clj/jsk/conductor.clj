(ns jsk.conductor
  "Coordination of workflows."
  (:require
            [jsk.quartz :as q]
            [clojurewerkz.quartzite.conversion :as qc]
            [jsk.workflow :as w]
            [jsk.db :as db]
            [jsk.ds :as ds]
            [jsk.graph :as g]
            [clojurewerkz.quartzite.scheduler :as qs]
            [clojure.string :as str]
            [clojure.core.async :refer [chan go go-loop put! >! <!]]
            [taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal)])
  (:import (org.quartz JobDataMap JobDetail JobExecutionContext JobKey Scheduler)))

;-----------------------------------------------------------------------
; exec-tbl tracks executions and the status of each job in the workflow.
; It is also used to determine what job(s) to trigger next.
; {execution-id :info IExecutionInfo
;               :running-jobs-count 1
;               :failed? false}
;
;-----------------------------------------------------------------------
(def ^:private exec-infos (atom {}))

(def ^:private cond-chan (atom nil))

(def ^:private info-chan (atom nil))


;-----------------------------------------------------------------------
; Execution Info interactions
;-----------------------------------------------------------------------
(defn- add-exec-info!
  "Adds the execution-id and table to the exec-info. Also
   initailizes the running jobs count property."
  [exec-id info]
  (let [exec-wf-counts (zipmap (ds/workflows info) (repeat 0))]
    (swap! exec-infos assoc exec-id {:info info
                                     :running-jobs exec-wf-counts
                                     :failed-exec-wfs #{}})))
(defn- rm-exec-info!
  "Removes the execution-id and all its data from the in memory store"
  [exec-id]
  (swap! exec-infos dissoc exec-id))

(defn get-exec-info
  "Look up the IExecutionInfo for the exec-id."
  [exec-id]
  (get-in @exec-infos [exec-id :info]))

(defn get-by-exec-id [id] (get @exec-infos id))

(defn- update-running-jobs-count!
  "Updates the running jobs count based on the value of :execution-id.
  'f' is a fn such as inc/dec for updating the count.  Answers with the
  new running job count for the exec-wf-id in execution-id."
  [execution-id exec-wf-id f]
  (debug "update running jobs count with: execution-id: " execution-id ", exec-wf-id: " exec-wf-id ", f:" f)
  (let [path [execution-id :running-jobs exec-wf-id]]
    (-> (swap! exec-infos update-in path f)
        (get-in path))))

(defn- running-jobs-count
  [execution-id exec-wf-id]
  (get-in @exec-infos [execution-id :running-jobs exec-wf-id]))

(defn- mark-exec-wf-failed!
  "Marks the exec-wf-id in exec-id as failed."
  [exec-id exec-wf-id]
  (swap! exec-infos update-in [exec-id :failed-exec-wfs] conj exec-wf-id))

(defn- exec-wf-failed?
  "Answers if the exec-wf failed"
  [exec-id exec-wf-id]
  (let [fails (get-in @exec-infos [exec-id :failed-exec-wfs])]
    (if (fails exec-wf-id)
      true
      false)))

(def ^:private exec-wf-success? (complement exec-wf-failed?))


(defn- execute-job  [^JobKey job-key ^JobDataMap job-data-map]
    (.triggerJob ^Scheduler @qs/*scheduler* job-key job-data-map))

(defn- run-job
  ([job-id] (run-job job-id {}))
  ([job-id data]
    (execute-job (q/make-job-key job-id) (qc/to-job-data data))))


(defn- run-jobs
  "Fires off each exec vertex id in vertices.  exec-id is required to figure out
   the exec-vertex-id which serves as the unique id for writing log files
   to.  exec-vertex-id is used by the execution.clj ns."
  [vertices exec-wf-id exec-id]
  (let [info (get-exec-info exec-id)]
    (doseq [v vertices
            :let [{:keys[node-id node-nm]} (ds/vertex-attrs info v)
                  data {:execution-id exec-id
                        :exec-wf-id exec-wf-id
                        :node-id node-id
                        :node-nm node-nm
                        :trigger-src :conductor
                        :start-ts (db/now)
                        :exec-vertex-id v}]]
      (run-job (:node-id data) data))))


;-----------------------------------------------------------------------
; Runs all workflows with the execution-id specified.
;-----------------------------------------------------------------------
(defn- run-workflows [wfs exec-id]
  (let [info (get-exec-info exec-id)]
    (doseq [wf wfs
            :let [data {:event :run-wf
                        :execution-id exec-id
                        :exec-wf-id (:exec-wf-to-run (ds/vertex-attrs info wf))
                        :exec-vertex-id wf
                        :trigger-src :conductor}]]
      (put! @cond-chan data))))

;-----------------------------------------------------------------------
; Runs all nodes handling jobs and workflows slightly differently.
;-----------------------------------------------------------------------
(defn- run-nodes
  "Fires off each node in node-ids.  execution-id is required to figure out
   the exec-vertex-id which serves as the unique id for writing log files
   to.  exec-vertex-id is used by the execution.clj ns."
  [node-ids exec-id]

  ; group-by node-ids by node-type
  (let [info (get-exec-info exec-id)
        [wf-id & others] (-> (ds/workflow-context info node-ids) vals distinct)
        f (fn[id](->> id (ds/vertex-attrs info) :node-type))
        type-map (group-by f node-ids)]

    (debug "run-nodes: node-ids:" node-ids)
    (debug "run-nodes: info:" info)
    (debug "run-nodes: type-map:" type-map)

    (assert (nil? others)
            (str "wf-id: " wf-id ", others: " others
                 ". Expected nodes to belong to only one wf."))

    ; FIXME: these could be nil if group by doesn't produce
    ; a value from type-map

    (-> db/job-type-id type-map (run-jobs wf-id exec-id))
    (-> db/workflow-type-id type-map (run-workflows exec-id))))

;-----------------------------------------------------------------------
; Start a workflow from scratch or within the execution.
;-----------------------------------------------------------------------
(defn- start-workflow-execution
  ([wf-id]
   (try
     (let [wf-name (w/get-workflow-name wf-id)
           {:keys[execution-id info]} (w/setup-execution wf-id)]
       (add-exec-info! execution-id info)
       (put! @info-chan {:event :execution-started
                         :execution-id execution-id
                         :wf-name wf-name})
       ; pass in nil for exec-vertex-id since the actual workflow is represented
       ; by the execution and is not a vertex in itself
       (start-workflow-execution nil (ds/root-workflow info) execution-id))
     (catch Exception e
       (error e))))

  ([exec-vertex-id exec-wf-id exec-id]
   (let [info (get-exec-info exec-id)
         roots (-> info (ds/workflow-graph exec-wf-id) g/roots)
         ts (db/now)]

     (db/workflow-started exec-wf-id ts)

     (if exec-vertex-id
       (db/execution-vertex-started exec-vertex-id ts))

     (put! @info-chan {:event :wf-started
                       :exec-vertex-id exec-vertex-id
                       :exec-wf-id exec-wf-id
                       :start-ts ts
                       :execution-id exec-id})
     (run-nodes roots exec-id))))

;-----------------------------------------------------------------------
; Find which things should execute next
;-----------------------------------------------------------------------
(defn- successor-nodes
  "Answers with the successor nodes for the execution status of node-id
  belonging to execution-id."
  [execution-id exec-vertex-id success?]
  (-> execution-id
      get-exec-info
      (ds/dependencies exec-vertex-id success?)))

;-----------------------------------------------------------------------
; Creates a synthetic workflow to run the specified job in.
;-----------------------------------------------------------------------
(defn- run-job-as-synthetic-wf
  "Runs the job in the context of a synthetic workflow."
  [job-id]
  (let [{:keys[execution-id info] :as m} (w/setup-synthetic-execution job-id)]
    (add-exec-info! execution-id info)
    (run-nodes (ds/vertices (get-exec-info execution-id))
               execution-id)))


;-----------------------------------------------------------------------
; Schedule job to be triggered now.
;-----------------------------------------------------------------------
(defn trigger-job-now [job-id]
  (info "job-id is " job-id)
  (put! @cond-chan {:event :trigger-job :node-id job-id :trigger-src :user})
  true)

;-----------------------------------------------------------------------
; Schedule workflow to be triggered now.
;-----------------------------------------------------------------------
(defn trigger-workflow-now
  [wf-id]
  (info "Triggering workflow with id: " wf-id)
  (put! @cond-chan {:event :trigger-wf :wf-id wf-id})
  true)


(defn- execution-finished [exec-id success? last-exec-wf-id]
  (let [root-wf-id (-> exec-id get-exec-info ds/root-workflow)
        ts (db/now)]
    (db/workflow-finished root-wf-id success? ts)
    (put! @info-chan {:event :wf-finished :execution-id exec-id :success? success?})
    (db/execution-finished exec-id success? ts)
    (put! @info-chan {:event :execution-finished :execution-id exec-id :success? success?})
    #_(rm-exec-info! exec-id)))


(defn- parents-to-upd [vertex-id execution-id success?]
  (loop [v-id vertex-id ans {}]
    (let [{:keys[parent-vertex on-success on-failure belongs-to-wf]}
          (ds/vertex-attrs (get-exec-info execution-id) v-id)
          deps (if success? on-success on-failure)
          running-count (running-jobs-count execution-id belongs-to-wf)]
      (if (and parent-vertex (empty? deps) (zero? running-count))
        (recur parent-vertex (assoc ans parent-vertex belongs-to-wf))
        ans))))

(defn- mark-wf-and-parent-wfs-finished
  "Finds all parent workflows which also need to be marked as completed."
  [exec-vertex-id exec-wf-id execution-id success?]
  (let [ts (db/now)
        vertices-wf-map (parents-to-upd exec-vertex-id execution-id success?)
        vertices (conj (keys vertices-wf-map) exec-vertex-id)
        wfs (conj (vals vertices-wf-map) exec-wf-id)]
    (info "Marking finished: Vertices:" vertices ", wfs:" wfs)
    (db/workflows-and-vertices-finished vertices wfs success? ts)

    ; FIXME: need to iterate over all the vertices and put on info-chan
    (put! @info-chan {:event :wf-finished :execution-id execution-id :success? success?})))


(defmulti dispatch :event)

(defmethod dispatch :trigger-job [{:keys [node-id trigger-src]}]
  (case trigger-src
    :quartz (run-job-as-synthetic-wf node-id)
    :user   (run-job-as-synthetic-wf node-id)
    :conductor (run-job node-id)))


(defmethod dispatch :trigger-wf [{:keys [wf-id]}]
  (start-workflow-execution wf-id))

(defmethod dispatch :run-wf [{:keys [exec-vertex-id exec-wf-id execution-id]}]
  (start-workflow-execution exec-vertex-id exec-wf-id execution-id))

(defmethod dispatch :wf-finished [{:keys[execution-id exec-wf-id exec-vertex-id]}]
  (let [wf-success? (exec-wf-success? execution-id exec-wf-id)
        next-nodes (successor-nodes execution-id exec-vertex-id wf-success?)
        exec-failed? (and (not wf-success?) (empty? next-nodes))
        exec-success? (and wf-success? (empty? next-nodes))]

    (mark-wf-and-parent-wfs-finished exec-vertex-id exec-wf-id execution-id wf-success?)

    (run-nodes next-nodes execution-id)

    ; execution finished?
    (if (or exec-failed? exec-success?)
      (execution-finished execution-id exec-success? exec-wf-id))))

; this comes from execution.clj
(defmethod dispatch :job-started [{:keys[execution-id exec-wf-id]}]
  (update-running-jobs-count! execution-id exec-wf-id inc))

; this comes from execution.clj
; decrements the running job count for the execution-id
; determines next set of jobs to run
; also determines if the workflow is finished and/or errored.
(defmethod dispatch :job-finished [{:keys[execution-id exec-wf-id exec-vertex-id success?] :as msg}]
  (debug "job-finished: " msg)
  (let [new-count (update-running-jobs-count! execution-id exec-wf-id dec)
        next-nodes (successor-nodes execution-id exec-vertex-id success?)
        exec-wf-fail? (and (not success?) (empty? next-nodes))
        exec-wf-finished? (and (zero? new-count) (empty? next-nodes))
        {:keys[parent-vertex]} (-> (get-exec-info execution-id)
                                   (ds/vertex-attrs exec-vertex-id))]

    (if exec-wf-fail?
      (mark-exec-wf-failed! execution-id exec-wf-id))

    (if exec-wf-finished?
      (put! @cond-chan {:event :wf-finished
                        :exec-vertex-id parent-vertex
                        :execution-id execution-id
                        :success? (not exec-wf-fail?)
                        :exec-wf-id exec-wf-id})
      (run-nodes next-nodes execution-id))))


(defn init
  "Sets up a queue which other parts of the program can write
   messages to. A message is a map with at least an :event key.
   Rest of the keys will depend on the event and what's relevant."
  [cond-ch info-ch]

  (reset! cond-chan cond-ch)
  (reset! info-chan info-ch)

  (go-loop [msg (<! cond-ch)]
     (try
       (dispatch msg)
       (catch Exception ex
         (error ex)))
     (recur (<! cond-ch))))


