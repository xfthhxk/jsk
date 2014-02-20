(ns jsk.common.alert
  (:require [taoensso.timbre :as log]
            [bouncer [core :as b] [validators :as v]]
            [jsk.common.db :as db]
            [korma.db :as k]
            [jsk.common.util :as util]
            [clojure.core.async :refer [put!]])
  (:use [bouncer.validators :only [defvalidator]]))

(def ^:private out-chan (atom nil))

(defn init
  "Sets the channel to use when updates are made to alerts or associations."
  [ch]
  (reset! out-chan ch))


;-----------------------------------------------------------------------
; Alert lookups
;-----------------------------------------------------------------------
(defn ls-alerts
  []
  "Lists all alerts"
  (db/ls-alerts))

(defn get-alert
  "Gets a alert for the id specified"
  [id]
  (db/get-alert id))

(defn get-alerts [ids]
  (db/get-alert ids))

(defn get-alert-by-name
  "Gets a alert by name if one exists otherwise returns nil"
  [nm]
  (db/get-alert-by-name nm))


(defn alert-name-exists?
  "Answers true if alert name exists"
  [nm]
  (-> nm db/get-alert-by-name nil? not))

(def new-alert-name? (complement alert-name-exists?))

;-----------------------------------------------------------------------
; Validates if the s-name can be used
;-----------------------------------------------------------------------
(defn unique-name?  [id sname]
  (if-let [s (db/get-alert-by-name sname)]
    (= id (:alert-id s))
    true))

; NB the first is used to see if bouncer generated any errors
; bouncer returns a vector where the first item is a map of errors
(defn validate-save [{:keys [alert-id] :as s}]
  (-> s
    (b/validate
       :alert-name [v/required [(partial unique-name? alert-id) :message "Alert name must be unique."]])
    first))



(defn- save-alert* [{:keys [alert-id] :as s} user-id]
  (if (db/id? alert-id)
      (db/update-alert! s user-id)
      (db/insert-alert! s user-id)))

;-----------------------------------------------------------------------
; Saves the alert either inserting or updating depending on the
; alert-id. If it is negative insert otherwise update.
;-----------------------------------------------------------------------
(defn save-alert! [s user-id]
  (if-let [errors (validate-save s)]
    (util/make-error-response errors)
    (let [s-id (save-alert* s user-id)]
      (put! @out-chan {:msg :alert-save :alert-id s-id}) ; this will be published to conductor
      {:success? true :alert-id s-id})))


;-----------------------------------------------------------------------
; Associates a job to a set of alert-ids.
; alert-ids is a set of integer ids
;-----------------------------------------------------------------------
(defn assoc-alerts!
  ([{:keys [node-id alert-ids]} user-id]
    (assoc-alerts! node-id alert-ids user-id))

  ([node-id alert-ids user-id]
     (let [node-alert-ids (db/node-alert-ids-for-node node-id)]
       (log/info "user-id " user-id " requests job-id " node-id " be associated with alerts " alert-ids)

       (k/transaction
         (db/rm-node-alerts! node-alert-ids)
         (db/assoc-alerts! node-id alert-ids user-id))

       (put! @out-chan {:msg :alert-assoc :node-id node-id})

       (log/info "job alert associations made for job-id: " node-id)
       true)))
