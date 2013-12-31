(ns jsk.messaging
  (:refer-clojure :exclude [read])
  (:require [nanomsg :as nn]
            [clojure.string :as string]
            [taoensso.timbre :as log]))

(defn- ->end-point [transport host port]
  (str transport "://" host ":" port))



(defn make-socket
  "Bind or connect to the end point specified by transport, host and port.
   bind? should be true for the stable parts of the topology."
  [transport host port bind? socket-type]
  (let [ep (->end-point transport host port)
        s (nn/socket socket-type)]
    (if bind?
      (nn/bind s ep)
      (nn/connect s ep))))

(defn subscribe [sock topics]
  (doseq [t topics]
    (nn/subscribe sock t)))


(defn subscribe-everything [sock]
  (nn/subscribe sock ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; A message is: ${topic}\u0000${edn}
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn publish
  "Publishes data to the socket on the topic specified."
  [socket topic data]
  (let [msg (str topic "\u0000" (pr-str data))]
    ;(log/debug "Publishing " msg)
    (nn/send socket msg)))


(defn parse
  "Splits the msg and returns the actual data.  The topic is removed.
   msg is 'topic\u0000{clj data-structure}"
  [msg]
  ;(log/debug "parse has: " msg)
  (let [[topic msg-str] (string/split msg #"\u0000")]
    ;(log/debug "Read " msg-str)
    (read-string msg-str)))


(defn read-pub-data [socket]
  (-> socket nn/recv parse))


(defn close!
  "Close all sockets present in socks seq."
  [sockets]
  (doseq [s sockets]
    (nn/close s)))
