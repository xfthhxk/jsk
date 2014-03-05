(ns jsk.common.notification
  (:require [taoensso.timbre :as log]
            [clojure.string :as string]
            [clojure.core.async :refer [chan go go-loop put! >! <!]]
            [jsk.common.conf :as conf])
  (:import (javax.mail Message Message$RecipientType MessagingException PasswordAuthentication Session Transport)
           (javax.mail.internet InternetAddress MimeMessage)
           (java.util Properties)))

(defonce ^:private mail-session (atom nil))
(defonce ^:private mail-chan (chan))

(defn- mail-config->Properties [host port]
  (doto (Properties.)
    (.put "mail.smtp.host" host)
    (.put "mail.smtp.port" (str port))
    (.put "mail.smtp.auth" "true")
    (.put "mail.smtp.starttls.enable" "true")))


(defn- make-authenticator [user pass]
  (proxy [javax.mail.Authenticator] []
    (getPasswordAuthentication [] (PasswordAuthentication. user pass))))



(defn- email->address
  "Converts an email address string to an instance of InternetAddress."
  [s]
  (InternetAddress. s))

(defn- make-mail-message [session from recipients subject body]
  (let [msg (MimeMessage. session)]

    (doto msg
      (.setFrom from)
      (.setSubject subject)
      (.setText body))

      (doseq [to recipients]
        (.addRecipient msg Message$RecipientType/TO to))
      msg))

(defn- recipients-string->addresses [to]
  (->> (string/split to #";")
       (map string/trim)
       (map email->address)))

(defn- mail* [to subject body]
  (let [{:keys[user]} (conf/mail-info)
        mail-from (email->address user)
        mail-recipients (recipients-string->addresses to)
        msg (make-mail-message @mail-session mail-from mail-recipients subject body)]
    (Transport/send msg)))

(defn mail
  "Synchronously sends an email"
  [to subject body]
  (try
    (mail* to subject body)
    (catch Exception ex
      (log/error ex))))

(defn enqueue-mail
  "Sends the mail in a background thread."
  [to subject body]
  (put! mail-chan {:to to :subject subject :body body}))

(defn sys-error [msg]
  (let [to (conf/error-email-to)
        subject "[JSK ERROR]"]
      (mail to subject msg)))

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

(defn- setup-mail-chan-processor []
  (go-loop [{:keys[to subject body]} (<! mail-chan)]
    (mail to subject body)
    (recur (<! mail-chan))))

(defn init
  "Initializes notifications."
  []
  (setup-mail-chan-processor)
  (let [{:keys [user pass host port]} (conf/mail-info)
        props (mail-config->Properties host port)
        auth (make-authenticator user pass)
        session (Session/getDefaultInstance props auth)]
    (reset! mail-session session)))
