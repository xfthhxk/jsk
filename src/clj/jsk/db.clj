(ns jsk.db
  "Database access"
  (:require [clojure.string :as str])
  (:use [korma core db config]))

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
(defn- now [] (java.util.Date.))

(set-delimiters "")
; lower case keywords for result sets
(set-naming {:keys keys-fn :fields fields-fn})

(declare job job_arg job_execution schedule job_schedule)


(defentity schedule
  (pk :schedule-id)
  (entity-fields :schedule-id :schedule-name :schedule-desc :cron-expression))


(defn ls-schedules
  []
  "Lists all schedules"
  (select schedule))

(defn get-schedule
  "Gets a schedule for the id specified"
  [id]
  (select schedule
          (where {:schedule-id id})))


(defn- insert-schedule! [m]
  (insert schedule
          (values (merge m {:created-at (:updated-at m)
                            :created-by (:updated-by m)}))))

(defn- update-schedule! [{:keys [schedule-id] :as m}]
  (update schedule
          (set-fields (dissoc m :schedule-id))
          (where {:schedule-id schedule-id})))


(defn save-schedule!
  ([{:keys [schedule-id schedule-name schedule-desc cron-expression]}]
   (save-schedule! schedule-id schedule-name schedule-desc cron-expression "amar"))

  ([id nm desc cron-expr user-id]
    (let [m {:schedule-name nm
             :schedule-desc desc
             :cron-expression cron-expr
             :updated-by user-id
             :updated-at (now)}]
      (if (neg? id)
        (insert-schedule! m)
        (update-schedule! (assoc m :schedule-id id))))))
