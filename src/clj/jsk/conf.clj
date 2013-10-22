(ns jsk.conf
  (:require [clojure.java.io :as io]))

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


(defn exec-log-dir []
  (@config :execution-log-directory))

(defn db-spec []
  (@config :db-spec))

; read the configuration
;(init "conf/jsk-config.clj")


; this seems to work from the repl
;(require '[clojure.java.io :as io])
;(io/resource "conf/jsk-conf.clj")
;(-> "conf/jsk-conf.clj" io/resource slurp read-string)
