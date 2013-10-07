(ns jsk.util
  (:require [jsk.user :as juser]
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal)]))


(defn validation-errors? [bouncer-result]
  (-> bouncer-result first nil? not))

(defn extract-validation-errors [bouncer-result]
  (first bouncer-result))

(defn make-error-response [errors]
  {:success? false :errors errors})


(def app-edn "application/edn")

(defn edn-request? [r]
  (let [ct (:content-type r)]
    (if ct
      (not= -1 (.indexOf ct app-edn))
      false)))


(def jsk-user-key :jsk/user)

(defn jsk-user-id [request]
  (-> request :session jsk-user-key))

;-----------------------------------------------------------------------
; friend/identity returns a map like the following
; This breaks it apart to get the relevant user info.
;-----------------------------------------------------------------------
; {:current https://www.google.com/accounts/o8/id?id=AItOawkPg2d-pw_lALU3V5B7CW01hKtFiqqd1nA,
; :authentications {https://www.google.com/accounts/o8/id?id=AItOawkPg2d-pw_lALU3V5B7CW01hKtFiqqd1nA {:email xfthhxk@gmail.com, :firstname Amar, :language en, :country US, :lastname Mehta
(defn lookup-authenticated-user [friend-identity]
  (info "friend-identity: " friend-identity)
  (let [{:keys [current authentications]}  friend-identity
        {:keys [email]} (authentications current)]
    (info "Found email in auth: " email)
    (juser/get-by-email email)))
