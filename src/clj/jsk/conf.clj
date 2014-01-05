(ns jsk.conf
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [korma.db :as k]
            [korma.config :as kc ]))

(def config (atom {}))

(defn- load-config
  "Reads the config file from the classpath and returns the data as a clojure map."
  [file-name]
  (-> file-name io/resource slurp read-string))


(defn init [file-name]
  (let [new-conf (load-config file-name)]
    (reset! config new-conf)))


(defn mail-info []
  (@config :email-conf))

(defn error-email-to []
  (@config :error-email-to))


(defn heartbeats-dead-after-ms
  "Max allowed time in milliseconds after which an agent is considered dead."
  []
  (get-in @config [:heartbeats :dead-after-milliseconds]))

(defn heartbeats-interval-ms
  "Heartbeat interval in milliseconds"
  []
  (get-in @config [:heartbeats :interval-milliseconds]))

(defn exec-log-dir []
  (@config :execution-log-directory))

(defn db-spec []
  (@config :db-spec))

(defn init-db []
  (k/defdb jsk-db (db-spec)) ; TODO: better way than in a fn?
  (kc/set-delimiters "")
  ; lower case keywords for result sets
  (kc/set-naming {:keys #(-> % (str/lower-case) (str/replace "_" "-"))
                  :fields #(-> % (str/lower-case) (str/replace "-" "_"))}))


; read the configuration
;(init "conf/jsk-config.clj")


; this seems to work from the repl
;(require '[clojure.java.io :as io])
;(io/resource "conf/jsk-conf.clj")
;(-> "conf/jsk-conf.clj" io/resource slurp read-string)
