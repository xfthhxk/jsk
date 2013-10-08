(ns jsk.db
  "Database access"
  (:require [clojure.string :as str])
  (:use [korma db config]))

; not using any delimiters around table/col name otherwise h2 says table doesn't exist

(def db-url "tcp://localhost:9092/nio:~/projects/jsk/resources/db/jsk.db;AUTO_SERVER=TRUE")

(def db-spec {:classname "org.h2.Driver"
              :db db-url
              :subname db-url
              :user "sa"
              :subprotocol "h2"
              :password "" })


(defdb jsk-db db-spec)

; used to convert keys to column/field names
(defn- fields-fn [s]
  (-> s (str/lower-case) (str/replace "-" "_")))

; used to convert column/field names to keywords
(defn- keys-fn [s]
  (-> s (str/lower-case) (str/replace "_" "-")))

; current date time
(defn now [] (java.util.Date.))

(set-delimiters "")
; lower case keywords for result sets
(set-naming {:keys keys-fn :fields fields-fn})


;-----------------------------------------------------------------------
; Extracts the id for the last inserted row
;-----------------------------------------------------------------------
(defn extract-identity [m]
  (first (vals m))) ; doing in a db agnostic way

(defn id? [id]
  (and id (> id 0)))
