(ns jsk.views.triggers
  (:use [net.cgrand.enlive-html
         :only [deftemplate defsnippet content clone-for
                nth-of-type first-child do-> set-attr sniptest at emit*]])
  (:require
            [taoensso.timbre :as timbre :refer (info error)]
            [jsk.quartz :as q])
  (:import [java.util Date]))

(def ^:dynamic *link-sel* [[:.trigger-name-row (nth-of-type 1)] :> first-child])

(defsnippet trigger-model "jsk/views/triggers.html" *link-sel*  [trigger-name]
  [:td] (content trigger-name))

(deftemplate  trigger-list  "jsk/views/triggers.html" [trigger-names]
  [:#page-created-ts] (content (str (Date.)))
  [:tr.trigger-name-row] (clone-for [trigger-name trigger-names]
                       [:td.trigger-name] (content trigger-name)))

(defn triggers-fn []
  (try
    (let [trigger-names (trigger-list (q/ls-triggers))]
      (info trigger-names)
      trigger-names)
    (catch Exception e
      (error e)
      (.getMessage e))))

