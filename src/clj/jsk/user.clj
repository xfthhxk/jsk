(ns jsk.user
  (:require [jsk.db :as db]))

(defrecord AppUser [app-user-id first-name last-name email])

;-----------------------------------------------------------------------
; User lookups.
;-----------------------------------------------------------------------
(defn get-by-email
  "Looks up a user by email returns an AppUser record otherwise nil."
  [email]
  (if-let [m (db/user-for-email email)]
    (map->AppUser m)))
