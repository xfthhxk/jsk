(ns jsk.util
  (:require [enfocus.core :as ef]
            [clojure.string :as string])
  (:require-macros [enfocus.macros :as em]))

(def started-status 2)
(def success-status 3)
(def error-status 4)
(def unknown-status 6)

(def status-id-desc-map {1 "Not Started"
                         2 "Started"
                         3 "Successful"
                         4 "Errored"
                         5 "Aborted"
                         6 "Unknown"})

(defn status-id->desc
  "Translates id to string description"
  [id]
  (get status-id-desc-map id))

(defn executing-status? [id]
  (= started-status id))

(defn now-ts []
  (js/Date.now))

(defn str->int [s]
  (.parseInt js/window s))

(defn element-by-id [id]
  (.getElementById js/document id))

(defn element-exists? [id]
  (if (element-by-id id) true false))

(def element-not-exists? (complement element-exists?))

; extract the element that raised the event
(defn event-source [event]
  (.-currentTarget event))

(defn parent-node [n]
  (.-parentNode n))


; m is a map, kk are keys, parse each value associated with k to an int
(defn update-str->int [m & kk]
  (reduce (fn [coll k]
            (assoc coll k (-> k m str->int)))
          m
          kk))

(defn nan? [i]
  (.isNaN js/window i))


(defn element-value [id]
  (.-value (element-by-id id)))

(defn element-checked? [id]
  (.-checked (element-by-id id)))

(defn set-element-checked! [id]
  (set! (.-checked (element-by-id id)) true))


(defn str-contains?
  "Answers true if string s contains a substring x."
  [s x]
  (not= -1 (.indexOf s x)))


(defn ensure-coll
  "Ensures x is a coll otherwise wraps it in a vector"
  [x]
  (if (coll? x) x [x] ))



(em/deftemplate error-display-template :compiled "public/templates/error-list.html" [errors]
  "ul > :not(li:first-child)" (ef/remove-node)
  "ul > li" (em/clone-for [e errors]
              (ef/content e)))

; errors is a seq of msgs
(defn display-errors [errors]
  (println"errors received: " errors)
  (ef/at "#error-div" (ef/content (error-display-template errors))))

(defn clear-error []
  (ef/at "#error-div" (ef/content "")))

(defn nav-to-login-page []
  (set! (-> js/window .-location .-href) "login.html"))

(def host
  (aget js/window "location" "host"))

(defn hide-element [selector]
  (ef/at selector (ef/do->
                    (ef/remove-class "visible")
                    (ef/add-class "hidden"))))

(defn show-element [selector]
  (ef/at selector (ef/do->
                    (ef/remove-class "hidden")
                    (ef/add-class "visible"))))



(def container "#container")
(def executions "#executions-accordion")


(def visible-element (atom executions))

(defn- set-visible-element [e]
  (reset! visible-element e))

(defn- executions-visible? []
  (= executions @visible-element))

(def executions-hidden? (complement executions-visible?))

(defn- container-visible? []
  (= container @visible-element))

(def container-hidden? (complement container-visible?))

(defn- hide-executions []
  (when (executions-visible?)
    (set-visible-element "")
    (hide-element executions)))

(defn- show-executions []
  (when (executions-hidden?)
    (set-visible-element executions)
    (show-element executions)))

(defn- hide-container []
  (when (container-visible?)
    (set-visible-element "")
    (hide-element container)))

(defn- show-container []
  (when (container-hidden?)
    (set-visible-element container)
    (show-element container)))

(defn showcase [view]
  (hide-executions)
  (show-container)
  (ef/at "#container" (ef/content view)))

(defn show-explorer-node [view]
  (ef/at "#explorer-node-detail" (ef/content view)))

(defn display-executions []
  (hide-container)
  (show-executions))


(def ^:private root-parent-dir-id -1)

(defn- ->explorer-element-id [id node-type]
  (if (= root-parent-dir-id id)
    "#"
   (str "exp-" (name node-type) "-" id)))

(defn- explorer-element-id->id [elem-id]
  (if (= "#" elem-id)
    root-parent-dir-id
    (-> (string/split elem-id #"-") last str->int)))

(defn- explorer-element-id-dissect
  "Takes an elem-id of form 'exp-job-23' which means job with id 23
   and returns [:job 23] in this case.  When elem-id is '#' returns
   [:root -1]"
  [elem-id]
  (if (= "#" elem-id)
    [:root root-parent-dir-id]
    (let [[_ elem-type id-str] (string/split elem-id #"-")] 
      [(keyword elem-type) (str->int id-str)])))

(defn explorer-root-section-id [element-type]
  (->explorer-element-id 0 element-type))

(defn explorer-root-section? [element-id-str]
  (let [[element-type element-id] (explorer-element-id-dissect element-id-str)]
    (zero? element-id)))

(def synthetic-workflow-id 1)

(def job-type-id 1)
(def workflow-type-id 2)

(defn workflow-type? [id]
  (= workflow-type-id id))

(defn job-type? [id]
  (= job-type-id id))

(def ^:private node-type-kw-map {job-type-id :job workflow-type-id :workflow})
(defn node-type-id->keyword [node-type-id]
  (get node-type-kw-map node-type-id))

(defn pad-zero [i]
  (if (< i 10)
    (str "0" i)
    (str i)))

(def ^:private month->english
  {0 "Jan"
   1 "Feb"
   2 "Mar"
   3 "Apr"
   4 "May"
   5 "Jun"
   6 "Jul"
   7 "Aug"
   8 "Sep"
   9 "Oct"
   10 "Nov"
   11 "Dec"})

(defn format-ts
  [ts]
  (let [day (-> ts .getDate pad-zero)
        mnth (->> ts .getMonth (get month->english))
        yr (.getFullYear ts)
        hr (-> ts .getHours pad-zero)
        min (-> ts .getMinutes pad-zero)
        ss (-> ts .getSeconds pad-zero)]
    (str day "-" mnth "-" yr " " hr ":" min ":" ss)))
