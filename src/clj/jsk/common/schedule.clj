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
                   :cron-expression "1 1 1 1 1 ? 2100"}
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

(defn add-node-schedule-assoc!
  "Add a node schedule association"
  ([{:keys [node-id schedule-id]} user-id]
     (add-node-schedule-assoc! node-id schedule-id user-id))

  ([node-id schedule-id user-id]
    (let [node-schedule-id (db/insert-node-schedule! node-id schedule-id user-id)
          {:keys [node-name node-type-id]} (db/get-node-by-id node-id)
          {:keys [schedule-name]} (get-schedule schedule-id)]
      (put! @out-chan {:msg :schedule-assoc :node-id node-id})
      (put! @ui-chan {:crud-event :schedule-assoc-add
                      :node-schedule-id node-schedule-id
                      :node-id node-id
                      :node-name node-name
                      :node-type-id node-type-id
                      :schedule-id schedule-id
                      :schedule-name schedule-name}))))

(defn rm-node-schedule-assoc!
  "Removes a node schedule association"
  [node-schedule-id user-id]
  (log/info "User" user-id "is deleting schedule association" node-schedule-id)

  (let [{:keys [node-id schedule-id]} (db/get-node-schedule node-schedule-id)
        ns-count (-> node-id db/get-node-schedule-info count)]
    (db/rm-node-schedule! node-schedule-id)
    (put! @out-chan {:msg :schedule-assoc :node-id node-id})
    (put! @ui-chan {:crud-event :schedule-assoc-rm
                    :node-schedule-id node-schedule-id
                    :schedule-id schedule-id
                    :node-id node-id
                    :was-last-assoc? (= 1 ns-count)})))
