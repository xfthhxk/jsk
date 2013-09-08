(ns jsk.views.schedules
  (:use [net.cgrand.enlive-html :only [deftemplate content clone-for first-of-type]])
  (:require [noir.validation :as v]
            [taoensso.timbre :as timbre :refer (info error)]
            [jsk.quartz :as q])
  (:import [java.util Date]))


(deftemplate  schedule-list  "jsk/views/schedules.html" [schedule-names]
  [:#page-created-ts] (content (str (Date.)))
  [:tr.schedule-name-row] (clone-for [schedule-name schedule-names]
                       [:td.schedule-name] (content schedule-name)))

(defn schedules-fn []
  (try
    (let [schedule-names (schedule-list (map :name (q/find-schedules)))]
      (info schedule-names)
      schedule-names)
    (catch Exception e
      (error e)
      (.getMessage e))))

(deftemplate add-schedule-template "jsk/views/add-schedule.html" [{:keys [schedule-name schedule-desc cron-expr errors]}]
  [:#page-created-ts] (content (str (Date.)))
  [:#schedule-name] (content schedule-name)
  [:#schedule-desc] (content schedule-desc)
  [:#cron-expr] (content cron-expr)
  [:#errors] (fn [x] (if errors x nil)) ; excludes errors div if errors is nil
  [:li.error] (clone-for [err errors] (content err)))

(defn show-add-schedule [{:keys [schedule-name schedule-desc cron-expr] :as params}]
  (add-schedule-template params))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Returns a vector of validation errors if any or nil
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn validate-schedule-addition [schedule-name schedule-desc cron-expr]
  (v/rule (v/has-value? schedule-name) [:schedule-name "Schedule name required."])
  (v/rule (not (q/schedule-name-exists? schedule-name)) [:schedule-name "Schedule name already exists."])
  (v/rule (v/has-value? cron-expr) [:cron-expr "Cron expr required."])
  (if (v/errors?) (v/get-errors) nil))


(defn add-schedule! [{:keys[schedule-name schedule-desc cron-expr] :as params}]
  (info "in add-schedule! with params: " params)
  (if-let [errors (validate-schedule-addition schedule-name schedule-desc cron-expr)]
    (show-add-schedule (assoc params :errors errors))
    (q/add-schedule! schedule-name schedule-desc cron-expr)))
