(ns jsk.views.jobs
  (:use [net.cgrand.enlive-html
         :only [deftemplate defsnippet content clone-for
                nth-of-type first-child do-> set-attr sniptest at emit*]])
  (:require
            [taoensso.timbre :as timbre :refer (info error)]
            [jsk.core :as qs])
  (:import [java.util Date]))

(def ^:dynamic *link-sel* [[:.job-name-row (nth-of-type 1)] :> first-child])

(defsnippet job-model "jsk/views/jobs.html" *link-sel*  [job-name]
  [:td] (content job-name))

(deftemplate  jobs-list  "jsk/views/jobs.html" [job-names]
  [:#page-created-ts] (content (str (Date.)))
  [:tr.job-name-row] (clone-for [job-name job-names]
                       [:td.job-name] (content job-name)))

(defn jobs-fn-test []
  (let [jn (str "Run my calendar job: " (Date.))]
    (jobs-list {:job-name jn})))

(defn jobs-fn1 []
  (content (map jobs-list (qs/ls-jobs))))

(defn jobs-fn []
  (try
    (let [job-names (jobs-list (qs/ls-jobs))]
      (info job-names)
      job-names)
    (catch Exception e
      (error e)
      (.getMessage e))))


