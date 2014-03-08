(ns jsk.agent
  (:require [jsk.rfn :as rfn]
            [jsk.util :as util]
            [cljs.core.async :as async :refer [<!]]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))

(defn save-agent [e]
  (go
    (let [form (ef/from "#agent-save-form" (ef/read-form))
          data (util/update-str->int form :agent-id)
          {:keys [success? agent-id errors] :as save-result} (<! (rfn/save-agent data))]
      (println "Result: " save-result)
      (when (seq errors)
        (util/display-errors (-> errors vals flatten))))))

(em/defsnippet edit-agent :compiled "public/templates/agents.html" "#agent-edit" [{:keys [agent-id agent-name]}]
  "#agent-id"     (ef/set-attr :value (str agent-id))
  "#agent-id-lbl" (ef/content (str agent-id))
  "#agent-name"   (ef/set-attr :value agent-name)
  "#save-btn"     (events/listen :click save-agent))

(defn show-agent-details [agent-id]
  (go
   (let [agent-data (<! (rfn/fetch-agent-details agent-id))]
     (util/show-explorer-node (edit-agent agent-data)))))


