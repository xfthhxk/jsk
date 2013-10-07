(ns jsk.user
  (:require [taoensso.timbre :as timbre :refer (info warn error)]
            [bouncer [core :as b] [validators :as v]]
            [jsk.db :as jdb]
            [jsk.quartz :as q])
  (:use [korma core]
        [bouncer.validators :only [defvalidator]]
        [swiss-arrows core]))


(defentity app-user
  (pk :app-user-id)
  (entity-fields :app-user-id :first-name :last-name :email))


(defrecord AppUser [app-user-id first-name last-name email])


;-----------------------------------------------------------------------
; User lookups.
;-----------------------------------------------------------------------
(defn get-by-email
  "Looks up a user by email returns an AppUser record otherwise nil."
  [email]
  (if-let [m (first (select app-user (where {:email email})))]
    (merge (AppUser. nil nil nil nil) m)
    nil))
