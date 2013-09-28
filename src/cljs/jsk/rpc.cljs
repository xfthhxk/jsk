(ns jsk.rpc
  (:require [goog.net.XhrIo :as xhr]
            [goog.Uri :as uri]
            [goog.events :as events]
            [goog.structs :as structs]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(defn- log [x]
  (.log js/console x))

(defn- success? [status]
  (some #{status} [200 201 202 204 205 206]))

(defn- nil-or-empty? [s]
  (or (nil? s)
      (-> s count zero?)))

(defn- response->edn [s]
  (if (nil-or-empty? s)
    nil
    (reader/read-string s)))

(defn- rpc-event->response-map [e]
  (let [xhr (.-target e)
        status (.getStatus xhr)
        response (.getResponseText xhr)]
    {:xhr xhr :status status :response (response->edn response)}))

(def edn-headers (-> {"Content-Type" "application/edn"} clj->js structs/Map.))

(defn generic-error-handler [status-code text]
  (log (str "ERROR status: " status-code ", text: " text)))

(defn- cb-handler [e cb cbe]
  (let [{:keys [status response]} (rpc-event->response-map e)]
    (if (success? status)
      (cb response)
      (cbe status response))))


(defn rpc-call [method url data cb cbe]
  (let [sendable-data (pr-str data)
        when-xhr-complete (fn [e] (cb-handler e cb cbe))]
   (goog.net.XhrIo/send url when-xhr-complete  method sendable-data edn-headers)))


(defn GET [url cb]
  (rpc-call "GET" url "" cb generic-error-handler))

(defn POST [url data cb]
  (rpc-call "POST" url data cb generic-error-handler))
