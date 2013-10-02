(ns jsk.util)

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
