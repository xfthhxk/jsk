(ns jsk.console.explorer
  "Explorer for the console ui."
  (:require [jsk.common.db :as db]
            [jsk.common.util :as util]
            [jsk.common.data :as data]
            [jsk.common.job :as job]
            [jsk.common.agent :as agent]
            [jsk.common.alert :as alert]
            [jsk.common.schedule :as schedule]
            [jsk.common.workflow :as workflow]
            [clojure.string :as string]
            [clojure.core.async :refer [put!]]
            [taoensso.timbre :as log]))

(defonce ^:private ui-chan (atom nil))

(defn init
  "Sets the channel to use when updates are made to jobs."
  [ch]
  (reset! ui-chan ch))

;;-----------------------------------------------------------------------
;; create directory
;; later:
;; move a directory to another parent
;;-----------------------------------------------------------------------

(defn directory-exists?
  "Answers if dir with dir-name already exists within the parent dir."
  [dir-name parent-dir-id]
  (db/directory-exists? dir-name parent-dir-id))

(defn save-directory!
  "Saves the directory and returns the dir-id just created/updated.
   When :parent-directory-id is nil the directory created is a new root directory."
  [{:keys [directory-id directory-name parent-directory-id] :as dir-map}]
   (log/info "save-directory! " dir-map)
   (if (directory-exists? directory-name parent-directory-id)
     (util/make-error-response ["Directory already exists."])
     (let [dir-id (db/save-directory directory-id directory-name parent-directory-id)
           event-msg (merge dir-map {:crud-event :directory-save :directory-id dir-id})]
       (put! @ui-chan event-msg))))

(defn new-empty-job!
  "Makes a new empty job with default values and associates it to be a child
   of the specified directory-id."
  [dir-id user-id]
  (let [agent-id (-> (agent/ls-agents) first :agent-id)]
    (job/new-empty-job! dir-id agent-id user-id)))
    
(defn new-empty-workflow!
  "Makes a new empty workflow with default values and associates it to be a child
   of the specified directory-id."
  [dir-id user-id]
  (workflow/new-empty-workflow! dir-id user-id))

(defn new-empty-schedule!
  "Makes a new empty schedule"
  [user-id]
  (schedule/new-empty-schedule! user-id))

(defn new-empty-agent!
  "Makes a new empty agent"
  [user-id]
  (agent/new-empty-agent! user-id))

(defn new-empty-alert!
  "Makes a new empty alert"
  [user-id]
  (alert/new-empty-alert! user-id))

(defn rm-node!
  "Removes any relationships to the node and deletes the node."
  [node-id user-id]
  (let [references (db/workflows-referencing-node node-id)
        ref-csv (string/join ", " references)]
    (if (seq references)
      (util/make-error-response [(str "Unable to delete. Referenced in the following: " ref-csv)])
      (let [{:keys [node-type-id]} (db/rm-node! node-id)
            node-type (util/node-type-id->kw node-type-id)]
        (put! @ui-chan {:crud-event :element-rm :element-type node-type :element-id node-id})
        {:success? true :errors ""}))))

(defn rm-directory!
  "Delete directory as long as there are no directories with this dir-id as the parent
   and no nodes reference this dir-id."
  [dir-id user-id]
  (if (db/empty-directory? dir-id)
    (do
      (db/rm-directory dir-id)
      (put! @ui-chan {:crud-event :element-rm :element-type :directory :element-id dir-id})
      {:success? true :error ""})
    {:success? false :error "Non-empty directory."}))



(defn ls-directory
  "Gets the contents of the directory."
  ([] (ls-directory data/root-directory-id)) ; nil means root
  ([directory-id]
     (db/explorer-info directory-id)))

(defn change-parent-directory
  "Change the parent directory / ownfor the job/workflow/directory specified by element-id.
   element-type is :job :workflow or :directory."
  [{:keys [element-id element-type new-parent-directory-id] :as msg} user-id]

  (case element-type
    :directory (db/change-directory-parent element-id new-parent-directory-id)
    :job       (db/change-node-owning-directory element-id new-parent-directory-id)
    :workflow  (db/change-node-owning-directory element-id new-parent-directory-id))

  (let [element-name (if (= :directory element-type)
                       (-> element-id db/get-directory-by-id :node-directory-name)
                       (-> element-id db/get-node-by-id :node-name))]
    ;; push update to the ui
    (put! @ui-chan (assoc msg :crud-event :directory-change :element-name element-name)))
  {:success? true :errors ""})


