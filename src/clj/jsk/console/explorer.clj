(ns jsk.console.explorer
  "Explorer for the console ui."
  (:require [jsk.common.db :as db]
            [jsk.common.util :as util]
            [jsk.common.job :as job]
            [jsk.common.workflow :as workflow]
            [clojure.core.async :refer [put!]]
            [taoensso.timbre :as log]))

(def ^:private out-chan (atom nil))

(defn init
  "Sets the channel to use when updates are made to jobs."
  [ch]
  (reset! out-chan ch))

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
  "Saves the directory and returns the dir-id just created/updated."
  ([{:keys [directory-id directory-name parent-directory-id]}]
     (save-directory! directory-id directory-name parent-directory-id))

  ([dir-id dir-name parent-dir-id]
    (if (directory-exists? dir-name parent-dir-id)
      (util/make-error-response ["Directory already exists."])
      (db/save-directory dir-id dir-name parent-dir-id))))
    

(defn save-directory-content!
  "Saves the node to the directory specified.  Removes any existing association.
   Answers with a map of success? and the new directory content id"
  ([{:keys [directory-id node-id]}]
     (save-directory-content! directory-id node-id))

  ([dir-id node-id]
    (let [{:keys [new-id old-id]} (db/save-directory-content! dir-id node-id)]

      (when old-id
        (put! @out-chan {:event :rm-directory-content :directory-content-id old-id}))

      (put! @out-chan {:event :add-directory-content :directory-content-id new-id})
      {:sucess? true :directory-content-id new-id})))

(defn make-node-in-dir
  [directory-id user-id mk-node-fn]
  (assert directory-id "nil directory-id")
  (let [node-id (mk-node-fn user-id)]
    (save-directory-content! directory-id node-id)))

(defn make-new-empty-job!
  "Makes a new empty job with default values and associates it to be a child
   of the specified directory-id."
  [{:keys [directory-id]} user-id]
  (make-node-in-dir directory-id user-id job/make-new-empty-job!))
    
(defn make-new-empty-workflow!
  "Makes a new empty job with default values and associates it to be a child
   of the specified directory-id."
  [{:keys [directory-id]} user-id]
  (make-node-in-dir directory-id user-id workflow/make-new-empty-workflow!))


