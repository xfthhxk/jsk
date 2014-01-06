(ns jsk.notification
  (:require [taoensso.timbre :as log]
            [clojure.string :as string]
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
      (log/error ex))))

(defn job-error [{:keys [job-name execution-id error]}]
  (if error
    (let [to (conf/error-email-to)
          subject (str "[JSK ERROR] " job-name)
          body (str "Job execution ID: " execution-id "\n\n" error)]
      (log/info "Sending error email for execution: " execution-id)
      (mail to subject body))))

(defn dead-agents [agent-ids vertex-ids]
  "Sends an email about the agent disconnect."
  [agent-ids vertex-ids]
  (let [to (conf/error-email-to)
        subject "[JSK AGENT DISCONNECT]"
        body (str "Agents: " (string/join ", " agent-ids)
                  "\n\n Execution job-ids:" (string/join ", " vertex-ids))]
    (mail to subject body)))
