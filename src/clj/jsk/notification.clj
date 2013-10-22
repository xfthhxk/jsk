(ns jsk.notification
  (:require [taoensso.timbre :refer (info warn error)]
            [jsk.conf :as conf])
  (:import (javax.mail Message Message$RecipientType MessagingException PasswordAuthentication Session Transport)
           (javax.mail.internet InternetAddress MimeMessage)
           (java.util Properties)))

(def mail-session (atom nil))

(defn- mail-config->Properties [host port]
  (doto (Properties.)
    (.put "mail.smtp.host" host)
    (.put "mail.smtp.port" (str port))
    (.put "mail.smtp.auth" "true")
    (.put "mail.smtp.starttls.enable" "true")))


(defn- make-authenticator [user pass]
  (proxy [javax.mail.Authenticator] []
    (getPasswordAuthentication [] (PasswordAuthentication. user pass))))


(defn init
  "Initializes notifications."
  []
  (let [{:keys [user pass host port]} (conf/mail-info)
        props (mail-config->Properties host port)
        auth (make-authenticator user pass)
        session (Session/getDefaultInstance props auth)]
    (reset! mail-session session)))


(defn- email->address
  "Converts an email address string to an instance of InternetAddress."
  [s]
  (InternetAddress. s))

(defn- make-mail-message [session from to subject body]
  (doto (MimeMessage. session)
    (.setFrom from)
    (.addRecipient Message$RecipientType/TO to)
    (.setSubject subject)
    (.setText body)))

(defn- mail* [to subject body]
  (let [{:keys[user]} (conf/mail-info)
        mail-from (email->address user)
        mail-to (email->address to)
        msg (make-mail-message @mail-session mail-from mail-to subject body)]
    (Transport/send msg)))

(defn mail [to subject body]
  (try
    (mail* to subject body)
    (catch Exception ex
      (error ex))))
