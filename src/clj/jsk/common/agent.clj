(ns jsk.common.agent
  (:require [taoensso.timbre :as log]
            [bouncer [core :as b] [validators :as v]]
            [jsk.common.db :as db]
            [clojure.string :as string]
            [jsk.common.util :as util]
            [clojure.core.async :refer [put!]])
  (:use [bouncer.validators :only [defvalidator]]))

(def ^:private out-chan (atom nil))
(def ^:private ui-chan (atom nil))

(defn init
  "Sets the channel to use for publishing updates."
  [ch ui-ch]
  (reset! out-chan ch)
  (reset! ui-chan ui-ch))


;-----------------------------------------------------------------------
; Agent lookups
;-----------------------------------------------------------------------
(defn ls-agents
  "Lists all agents"
  []
  (db/ls-agents))

(defn get-agent
  "Gets an agent for the id specified"
  [id]
  (db/get-agent id))

(defn get-agents [ids]
  (db/get-agents ids))

(defn get-agent-by-name
  "Gets a agent by name if one exists otherwise returns nil"
  [nm]
  (db/get-agent-by-name nm))


(defn agent-name-exists?
  "Answers true if agent name exists"
  [nm]
  (-> nm db/get-agent-by-name nil? not))

(def new-agent-name? (complement agent-name-exists?))

;-----------------------------------------------------------------------
; Validates if the s-name can be used
;-----------------------------------------------------------------------
(defn unique-name?  [id sname]
  (if-let [s (db/get-agent-by-name sname)]
    (= id (:agent-id s))
    true))

; NB the first is used to see if bouncer generated any errors
; bouncer returns a vector where the first item is a map of errors
(defn validate-save [{:keys [agent-id] :as a}]
  (-> a
    (b/validate
       :agent-name [v/required [(partial unique-name? agent-id) :message "Agent name must be unique."]])
    first))



(defn- save-agent* [{:keys [agent-id] :as a} user-id]
  (if (db/id? agent-id)
      (db/update-agent! a user-id)
      (db/insert-agent! a user-id)))

;-----------------------------------------------------------------------
; Saves the agent either inserting or updating depending on the
; agent-id. If it is negative insert otherwise update.
;-----------------------------------------------------------------------
(defn save-agent! [{:keys [agent-name] :as a} user-id]
  (if-let [errors (validate-save a)]
    (util/make-error-response errors)
    (let [a-id (save-agent* a user-id)]
      (put! @out-chan {:msg :agent-save :agent-id a-id}) ; this will be published to conductor
      (put! @ui-chan {:crud-event :agent-save :agent-id a-id :agent-name agent-name}) ; this will be published to UIs
      {:success? true :agent-id a-id})))


(defn new-empty-agent! [user-id]
  (save-agent! {:agent-id -1
                :agent-name (str "Agent " (util/now-ms))}
               user-id))

(defn rm-agent! [agent-id user-id]
  (let [references (db/jobs-referencing-agent agent-id)
        ref-csv (string/join ", " references)]
    (if (seq references)
      (util/make-error-response [(str "Unable to delete. Referenced in the following: " ref-csv)])
      (do
        (db/rm-agent! agent-id)
        (put! @ui-chan {:crud-event :agent-rm :agent-id agent-id})
        {:success? true :errors ""}))))
