(ns jsk.agent
  (:require [jsk.rfn :as rfn]
            [jsk.util :as util]
            [cljs.core.async :as async :refer [<!]]
            [enfocus.core :as ef]
            [enfocus.events :as events]))
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))


(declare agent-row-clicked save-agent)

;-----------------------------------------------------------------------
; List all agents
;-----------------------------------------------------------------------
(em/defsnippet list-agents :compiled "public/templates/agents.html" "#agents-list" [aa]
  ; template has 2 sample rows, so delete all but the first
  ; and then apply the clone on the first child
  "tbody > :not(tr:first-child)" (ef/remove-node)
  "tbody > tr" (em/clone-for [{:keys [agent-id agent-name create-ts]} aa]
                 "td.agent-id" #(ef/at (util/parent-node %1)
                                          (ef/do->
                                            (ef/set-attr :data-agent-id (str agent-id))
                                            (events/listen :click agent-row-clicked)))
                 "td.agent-id" (ef/content (str agent-id ))
                 "td.agent-name" (ef/content agent-name)
                 "td.create-ts" (ef/content (str create-ts ))))

(em/defsnippet edit-agent :compiled "public/templates/agents.html" "#agent-edit" [{:keys [agent-id agent-name]}]
  "#agent-id"     (ef/set-attr :value (str agent-id))
  "#agent-id-lbl" (ef/content (str agent-id))
  "#agent-name"   (ef/set-attr :value agent-name)
  "#save-btn"     (events/listen :click save-agent))


(defn- show-agent-edit [a]
  (util/showcase (edit-agent a)))

(defn agent-row-clicked [e]
  (go
    (let [id (ef/from (util/event-source e) (ef/get-attr :data-agent-id))
          ad (<! (rfn/fetch-agent-details id))]
      (show-agent-edit ad))))


(defn show-agents []
  (go
   (let [aa (<! (rfn/fetch-all-agents))]
     (util/showcase (list-agents aa)))))


(defn show-add-agent []
  (show-agent-edit {:agent-id -1}))

(defn save-agent [e]
  (go
    (let [form (ef/from "#agent-save-form" (ef/read-form))
          data (util/update-str->int form :agent-id)
          {:keys [success? agent-id errors] :as save-result} (<! (rfn/save-agent data))]
      (util/log (str "Result: " save-result))
      (if success?
        (show-agents)
        (when errors
          (util/display-errors (-> errors vals flatten))
          (show-agent-edit form))))))
