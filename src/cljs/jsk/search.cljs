(ns jsk.search
  (:require [jsk.rfn :as rfn]
            [jsk.util :as ju]
            [jsk.workflow :as w]
            [cljs.core.async :as async :refer [<!]]
            [jayq.core :as jq]
            [enfocus.core :as ef]
            [enfocus.events :as events])
  (:use [jayq.core :only [$]])
  (:require-macros [enfocus.macros :as em]
                   [cljs.core.async.macros :refer [go]]))

(declare do-search)


(def ^:private datetime-ui-config
  (clj->js {:language "en"
            :format "Y-m-d H:i"}))

(defn- update-execution-id [f]
  (let [id (-> f :execution-id ju/str->int)
        ans (if (ju/nan? id)
              nil
              id)]
    (assoc f :execution-id ans)))

(defn- update-status-ids [f]
  (let [ids (-> f :status-ids)
        ans (if ids
              (ju/ensure-coll ids)
              nil)]
    (assoc f :status-ids (map ju/str->int ans))))

(defn- update-ts [f sel kw default]
  (let [sv (kw f)
        ans' (if sv
               (.parse js/Date sv)
               default)
        ans (if (ju/nan? ans') default ans')]
    (println "form is: " f)
    (println "sv is:" sv)
    (println "default is:" default)
    (assoc f kw ans)))

(defn- parse-form []
  (-> (ef/from "#executions-search-form" (ef/read-form))
      update-execution-id
      update-status-ids
      (update-ts "#start-ts" :start-ts (-> (js/Date.) .getTime))
      (update-ts "#finish-ts" :finish-ts nil)))

(defn- clear-results []
  (ef/at "#executions-serch-results-div" (ef/content "")))

;-----------------------------------------------------------------------
; Execution search functionality
;-----------------------------------------------------------------------
(em/defsnippet make-search-form :compiled "public/templates/search.html"  "#executions-search-div"  [statuses]
  "label.checkbox-inline" (em/clone-for [[id nm] statuses]
                               "input" #(ef/at (ju/parent-node %) (ef/append nm))
                               "input" (ef/set-attr :value (str id)))
  "#do-search" (events/listen :click #(do-search (parse-form))))

(em/defsnippet show-executions :compiled "public/templates/search.html" "#executions-table" [ee]
  "tbody > :not(tr:first-child)" (ef/remove-node)
  "tbody > tr" (em/clone-for [{:keys[execution-id execution-name start-ts finish-ts status-id]} ee]
                 "td.execution-id" #(ef/at (ju/parent-node %1)
                                           (events/listen :click (fn[e](w/show-execution-visualizer execution-id))))
                 "td.execution-id" (ef/content (str execution-id))
                 "td.execution-name" (ef/content execution-name)
                 "td.execution-status" (ef/content (ju/status-id->desc status-id))
                 "td.execution-start" (ef/content (str start-ts))
                 "td.execution-finish" (ef/content (str finish-ts))))

(defn- show-search-msg [msg]
  (ef/at "#search-info-div" (ef/content msg))
  (ju/show-element "#search-info-div"))

(defn- do-search [data]
  (clear-results)
  (go
   (let [results (<! (rfn/search-executions data))]
     (println results)
     (show-search-msg (str "Found: " (count results) " executions."))
     (ef/at "#executions-search-results-div" (ef/content (show-executions results))))))


(defn show-execution-search []
  (ju/showcase (make-search-form ju/status-id-desc-map))
  (clear-results)
  (-> "#start-ts" $ (.datetimepicker datetime-ui-config))
  (-> "#finish-ts" $ (.datetimepicker datetime-ui-config)))
