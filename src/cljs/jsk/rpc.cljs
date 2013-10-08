(ns jsk.rpc
  (:require [jsk.util :as ju]
            [goog.net.XhrIo :as xhr]
            [goog.Uri :as uri]
            [goog.events :as events]
            [goog.structs :as structs]
            [cljs.core.async :as async :refer [chan close! put!]]
            [cljs.reader :as reader]
            [clojure.string :as str])
  (:require-macros [cljs.core.async.macros :refer [go]]))


(def app-edn "application/edn")

(def edn-headers (-> {"Content-Type" app-edn} clj->js structs/Map.))

(defn- default-rpc-error-handler [status msg]
  (ju/log (str "ERROR status: " status ", msg: " msg)))

(def error-handler (atom default-rpc-error-handler))

(defn- success? [status]
  (some #{status} [200 201 202 204 205 206]))

(defn unauthorized? [status]
  (some #{status} [401 403]))

(defn edn-response? [xhr]
  (let [ct (.getResponseHeader xhr "Content-Type")]
    (ju/str-contains? ct app-edn)))

(defn- nil-or-empty? [s]
  (or (nil? s)
      (-> s count zero?)))

(defn- response->edn [s]
  (if (nil-or-empty? s)
    nil
    (reader/read-string s)))

(defn parse-response [xhr]
  (let [response (.getResponseText xhr)]
    (if (edn-response? xhr)
      (response->edn response)
      response)))

(defn- rpc-event->response-map [e]
  (let [xhr (.-target e)
        status (.getStatus xhr)
        response (.getResponseText xhr)]
    (ju/log (str "XHR status: " status ", response: " response))
    {:xhr xhr :status status :response (parse-response xhr)}))

(defn- cb-handler [e cb]
  (let [{:keys [status response]} (rpc-event->response-map e)]
    (if (success? status)
      (cb response)
      (@error-handler status response))))

(defn- make-async-cb-handler [ch]
  (fn [event]
    (let [{:keys [status response]} (rpc-event->response-map event)]
      (if (success? status)
        (do
          (put! ch response)
          (close! ch))
        (do
          (@error-handler status response)
          (close! ch))))))

(defn rpc-call [method url data cb]
  (let [sendable-data (pr-str data)
        when-xhr-complete (fn [e] (cb-handler e cb))]
   (goog.net.XhrIo/send url when-xhr-complete  method sendable-data edn-headers)))


(defn async-rpc-call [method url data cb]
  (let [sendable-data (pr-str data)]
   (goog.net.XhrIo/send url cb  method sendable-data edn-headers)))

(defn GET
  ([url]
   (let [ch (chan 1)]
     (async-rpc-call "GET" url "" (make-async-cb-handler ch))
     ch))

  ([url cb]
   (rpc-call "GET" url "" cb)))

(defn POST
  ([url data]
   (let [ch (chan 1)]
     (async-rpc-call "POST" url data (make-async-cb-handler ch))
     ch))

  ([url data cb]
   (rpc-call "POST" url data cb)))

(defn DELETE [url data cb]
  (rpc-call "DELETE" url data cb))
