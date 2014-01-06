(ns jsk.messaging
  (:refer-clojure :exclude [read])
  (:require [nanomsg :as nn]
            [clojure.string :as string]
            [jsk.util :as util]
            [clojure.core.async :refer [put! <! go-loop chan]]
            [taoensso.timbre :as log]))

(defn- ->end-point [transport host port]
  (str transport "://" host ":" port))

(def all-topics [""])


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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; A message is: ${topic}\u0000${edn}
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn publish
  "Publishes data to the socket on the topic specified."
  [socket topic data]
  (let [msg (str topic "\u0000" (pr-str data))]
    ;(log/debug "Publishing " msg)
    (nn/send socket msg)))


(defn- parse-data-string
  "Splits the msg and returns the actual data string.  The topic is removed.
   msg is 'topic\u0000{clj data-structure}. Returns the clj-data-structure as a string."
  [msg]
  (second (string/split msg #"\u0000")))

(defn read-pub-string [socket]
  (-> socket nn/recv parse-data-string))

(defn read-pub-data [socket]
  (-> socket read-pub-string read-string))


(defn close!
  "Close all sockets present in socks seq."
  [sockets]
  (doseq [s sockets]
    (nn/close s)))


(defn- relay-from-sub-socket
  [host port bind? topics ch]
  (let [sock (make-socket "tcp" host port bind? :sub)]
    (subscribe sock topics)
    (loop [data (read-pub-data sock)]
      (put! ch data)
      (recur (read-pub-data sock)))))


(defn read-channel [thread-name host port bind? topics]
  "Creates a socket and a channel to relays messages from.
  Returns the channel"
  (let [ch (chan)]
    (util/start-thread thread-name  #(relay-from-sub-socket host port bind? topics ch))
    ch))

(defn relay-writes
  "Creates a socket relays writes to the channel.
   Each message dropped on the channel must be a map with the keys :topic and :data."
  [ch host port bind?]
  (go-loop [sock (make-socket "tcp" host port bind? :pub)
            {:keys[topic data]} (<! ch)]
    (publish sock topic data)
    (recur sock (<! ch))))










