(ns jsk.common.alert
  (:require [taoensso.timbre :as log]
            [bouncer [core :as b] [validators :as v]]
            [jsk.common.db :as db]
            [korma.db :as k]
            [clojure.string :as string]
            [jsk.common.util :as util]
            [clojure.core.async :refer [put!]])
  (:use [bouncer.validators :only [defvalidator]]))

(defonce ^:private out-chan (atom nil))
(defonce ^:private ui-chan (atom nil))

(defn init
  "Sets the channel to use when updates are made to alerts or associations."
  [ch ui-ch]
  (reset! out-chan ch)
  (reset! ui-chan ui-ch)) 


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
  (if-let [a (db/get-alert-by-name sname)]
    (= id (:alert-id a))
    true))

; NB the first is used to see if bouncer generated any errors
; bouncer returns a vector where the first item is a map of errors
(defn validate-save [{:keys [alert-id] :as a}]
  (-> a
    (b/validate
       :alert-name [v/required [(partial unique-name? alert-id) :message "Alert name must be unique."]])
    first))



(defn- save-alert* [{:keys [alert-id] :as a} user-id]
  (if (db/id? alert-id)
      (db/update-alert! a user-id)
      (db/insert-alert! a user-id)))

;-----------------------------------------------------------------------
; Saves the alert either inserting or updating depending on the
; alert-id. If it is negative insert otherwise update.
;-----------------------------------------------------------------------
(defn save-alert! [{:keys [alert-name] :as a} user-id]
  (if-let [errors (validate-save a)]
    (util/make-error-response errors)
    (let [a-id (save-alert* a user-id)]
      (put! @out-chan {:msg :alert-save :alert-id a-id}) ; this will be published to conductor
      (put! @ui-chan {:crud-event :alert-save :alert-id a-id :alert-name alert-name}) ; this will be published to conductor
      {:success? true :alert-id a-id})))


(defn new-empty-alert! [user-id]
  (save-alert! {:alert-id -1
                :alert-name (str "Alert " (util/now-ms))
                :alert-desc ""
                :subject ""
                :body ""}
               user-id))

(defn rm-alert! [alert-id user-id]
  (let [references (db/nodes-referencing-alert alert-id)
        ref-csv (string/join ", " references)]
    (if (seq references)
      (util/make-error-response [(str "Unable to delete. Referenced in the following: " ref-csv)])
      (do
        (db/rm-alert! alert-id)
        (put! @ui-chan {:crud-event :alert-rm :alert-id alert-id})
        {:success? true :errors ""}))))


(defn add-node-alert-assoc!
  "Add a node alert association"
  ([{:keys [node-id alert-id]} user-id]
     (add-node-alert-assoc! node-id alert-id user-id))

  ([node-id alert-id user-id]
    (let [node-alert-id (db/insert-node-alert! node-id alert-id user-id)
          {:keys [alert-name]} (get-alert alert-id)]
      (put! @out-chan {:msg :alert-assoc :node-id node-id})
      (put! @ui-chan {:crud-event :alert-assoc-add
                      :node-alert-id node-alert-id
                      :node-id node-id
                      :alert-id alert-id
                      :alert-name alert-name}))))

(defn rm-node-alert-assoc!
  "Removes a node alert association"
  [node-alert-id user-id]
  (log/info "User" user-id "is deleting alert association" node-alert-id)

  (let [{:keys [node-id alert-id]} (db/get-node-alert node-alert-id)]
    (db/rm-node-alert! node-alert-id)
    (put! @out-chan {:msg :alert-assoc :node-id node-id})
    (put! @ui-chan {:crud-event :alert-assoc-rm
                    :node-alert-id node-alert-id
                    :alert-id alert-id
                    :node-id node-id})))
