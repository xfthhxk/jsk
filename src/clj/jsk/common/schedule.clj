(ns jsk.common.schedule
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
  "Sets the channel to use when updates are made to schedules or associations."
  [ch ui-ch]
  (reset! out-chan ch)
  (reset! ui-chan ui-ch))


;-----------------------------------------------------------------------
; Schedule lookups
;-----------------------------------------------------------------------
(defn ls-schedules
  []
  "Lists all schedules"
  (db/ls-schedules))

(defn get-schedule
  "Gets a schedule for the id specified"
  [id]
  (db/get-schedule id))

(defn get-schedules [ids]
  (db/get-schedule ids))

(defn get-schedule-by-name
  "Gets a schedule by name if one exists otherwise returns nil"
  [nm]
  (db/get-schedule-by-name nm))


(defn schedule-name-exists?
  "Answers true if schedule name exists"
  [nm]
  (-> nm db/get-schedule-by-name nil? not))

(def new-schedule-name? (complement schedule-name-exists?))

;-----------------------------------------------------------------------
; Validates if the s-name can be used
;-----------------------------------------------------------------------
(defn unique-name?  [id sname]
  (if-let [s (db/get-schedule-by-name sname)]
    (= id (:schedule-id s))
    true))

; NB the first is used to see if bouncer generated any errors
; bouncer returns a vector where the first item is a map of errors
(defn validate-save [{:keys [schedule-id] :as s}]
  (-> s
    (b/validate
       :schedule-name [v/required [(partial unique-name? schedule-id) :message "Schedule name must be unique."]]
       :cron-expression [v/required [util/cron-expr? :message "Invalid cron expression."]])
    first))



(defn- save-schedule* [{:keys [schedule-id] :as s} user-id]
  (if (db/id? schedule-id)
      (db/update-schedule! s user-id)
      (db/insert-schedule! s user-id)))

;-----------------------------------------------------------------------
; Saves the schedule either inserting or updating depending on the
; schedule-id. If it is negative insert otherwise update.
;-----------------------------------------------------------------------
(defn save-schedule! [{:keys [schedule-name] :as s} user-id]
  (if-let [errors (validate-save s)]
    (util/make-error-response errors)
    (let [s-id (save-schedule* s user-id)]
      (put! @out-chan {:msg :schedule-save :schedule-id s-id}) ; this will be published to conductor
      (put! @ui-chan {:crud-event :schedule-save
                      :schedule-id s-id
                      :schedule-name schedule-name}) ; this will be published to the UI
      {:success? true :schedule-id s-id})))

(defn new-empty-schedule! [user-id]
  (save-schedule! {:schedule-id -1
                   :schedule-name (str "Schedule " (util/now-ms))
                   :schedule-desc ""
                   :cron-expression "0 1 1 ? * 1L 1999-2000"}
                  user-id))


(defn rm-schedule! [schedule-id user-id]
  (let [references (db/nodes-referencing-schedule schedule-id)
        ref-csv (string/join ", " references)]
    (if (seq references)
      (util/make-error-response [(str "Unable to delete. Referenced in the following: " ref-csv)])
      (do
        (db/rm-schedule! schedule-id)
        (put! @ui-chan {:crud-event :schedule-rm :schedule-id schedule-id})
        {:success? true :errors ""}))))

;-----------------------------------------------------------------------
; Associates a job to a set of schedule-ids.
; schedule-ids is a set of integer ids
;-----------------------------------------------------------------------
(defn assoc-schedules!
  ([{:keys [node-id schedule-ids]} user-id]
    (assoc-schedules! node-id schedule-ids user-id))

  ([node-id schedule-ids user-id]
     (let [node-schedule-ids (db/node-schedule-ids-for-node node-id)]
       (log/info "user-id " user-id " requests job-id " node-id " be associated with schedules " schedule-ids)

       (k/transaction
         (db/rm-node-schedules! node-schedule-ids)
         (db/assoc-schedules! node-id schedule-ids user-id))

       (put! @out-chan {:msg :schedule-assoc :node-id node-id})

       (log/info "job schedule associations made for job-id: " node-id)
       true)))
