(ns jsk.search
  "Search for executions and other things."
  (:require
            [jsk.db :as db]
            [jsk.ds :as ds]
            [clj-time.core :as ctime]
            [clj-time.coerce :as coerce]
            [clojure.string :as string]
            [taoensso.timbre :as log]))


(defn- date-time->timestamp
  "xs is a seq of ints which can be used to represent a timestamp.
  ie (2013 12 16 20 21 00)"
  [xs]
  (if (zero? (count xs))
    nil
    (->> xs
         (apply ctime/date-time)
         coerce/to-long
         java.sql.Timestamp.)))

(defn- date->ts [d]
  (if d
    (-> d coerce/to-long java.sql.Timestamp.)))


(defn executions
  "Searches for executions in the range specified.
  exec-id is an int, exec-name is a string, status-ids a seq of ints.
  start-ts and finish-ts can be longs or dates"
  ([{:keys[execution-id execution-name start-ts finish-ts status-ids]}]
   (executions execution-id execution-name start-ts finish-ts status-ids))

  ([exec-id exec-name start-ts finish-ts status-ids]
   (let [name* (if (string/blank? exec-name)
                 nil
                 (string/trim exec-name))
         sts (date->ts start-ts)
         fts (date->ts finish-ts)]

     (if fts
       (assert sts "Start time required if specifying finish time."))

     (db/execution-search exec-id name* sts fts status-ids))))
