(ns jsk.common.agent
  (:require [taoensso.timbre :as log]
            [bouncer [core :as b] [validators :as v]]
            [jsk.common.db :as db]
            [jsk.common.util :as util]
            [clojure.core.async :refer [put!]])
  (:use [bouncer.validators :only [defvalidator]]))

(def ^:private out-chan (atom nil))

(defn init
  "Sets the channel to use for publishing updates."
  [ch]
  (reset! out-chan ch))


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
(defn save-agent! [a user-id]
  (if-let [errors (validate-save a)]
    (util/make-error-response errors)
    (let [s-id (save-agent* a user-id)]
      ;(put! @out-chan {:msg :agent-save :agent-id s-id}) ; this will be published to conductor
      {:success? true :agent-id s-id})))
