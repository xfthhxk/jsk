(ns jsk.util
  (:require [enfocus.core :as ef])
  (:require-macros [enfocus.macros :as em]))

(defn log [x]
  (.log js/console (str x)))

(defn str->int [s]
  (.parseInt js/window s))

(defn element-by-id [id]
  (.getElementById js/document id))

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
  (log (str "errors received: " errors))
  (ef/at "#error-div" (ef/content (error-display-template errors))))

(defn clear-error []
  (ef/at "#error-div" (ef/content "")))


(defn nav-to-login-page []
  (set! (-> js/window .-location .-href) "login.html"))


(def host
  (aget js/window "location" "host"))

