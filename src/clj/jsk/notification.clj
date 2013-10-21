(ns jsk.notification
  (:require [taoensso.timbre :refer (info warn error)])
  (:use [korma core])
  (:import (javax.mail Message Message$RecipientType MessagingException PasswordAuthentication Session Transport)
           (javax.mail.internet InternetAddress MimeMessage)
           (java.util Properties)))

(def mail-session (atom nil))
(def mail-config (atom {}))

(defn- fetch-mail-config []
  (first
   (exec-raw ["select *
                 from email_profile
                where email_profile_name = 'Google'"] :results)))

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
  (let [{:keys [email-user-id email-pass host port] :as conf} (fetch-mail-config)
        props (mail-config->Properties host port)
        auth (make-authenticator email-user-id email-pass)
        session (Session/getDefaultInstance props auth)]
    (info "mail-config: " conf)
    (reset! mail-config conf) ; FIXME: should these be refs?
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
  (let [{:keys[email-user-id]} @mail-config
        mail-from (email->address email-user-id)
        mail-to (email->address to)
        msg (make-mail-message @mail-session mail-from mail-to subject body)]
    (Transport/send msg)))

(defn mail [to subject body]
  (try
    (mail* to subject body)
    (catch Exception ex
      (error ex))))
