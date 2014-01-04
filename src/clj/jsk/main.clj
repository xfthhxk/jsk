(ns jsk.main
  (:require [ring.adapter.jetty :as jetty]
            [jsk.util :as ju]
            [jsk.agent :as jagent]
            [jsk.conf :as conf]
            [jsk.conductor :as conductor]
            [taoensso.timbre :as log]
            [com.postspectacular.rotor :as rotor]
            [jsk.notification :as n]
            [clojure.string :as string]
            [clojure.tools.cli :as cli]
            [clojure.tools.nrepl.server :as nrepl]
            [jsk.handler :as jsk])
  (:gen-class))

(def allowed-modes ["conductor" "agent" "web"])

(def cli-options
  [
   ["-c" "--cmd-port PORT" "Command Port number"
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]

   ["-s" "--status-port PORT" "Status Port number"
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]

   ["-w" "--web-app-port PORT" "Web app Port number"
    :default 8080
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]

   ["-n" "--nrepl-port PORT" "nRepl Port number"
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]

   ["-H" "--hostname HOST" "Remote host"]

   ["-m" "--mode MODE" "Mode to run in"
    :validate [#(some #{%} allowed-modes) (str "Must be one of " (string/join ", " allowed-modes))]]
   ])

(defn usage [options-summary]
  (->> ["Job Scheduling Kit"
        ""
        "Usage: jsk [options] --mode mode"
        ""
        "Options:"
        options-summary
        ""]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn exit [status msg]
  (println msg)
  (System/exit status))


;-----------------------------------------------------------------------
; Logging initalizer.
;-----------------------------------------------------------------------
(defn- init-logging []
  "Setup logging options"
  (log/set-config! [:appenders :rotor]
                     {:min-level :info
                      :enabled? true
                      :async? false                  ; should always be false for rotor
                      :max-message-per-msecs nil
                      :fn rotor/append})

  (log/set-config! [:shared-appender-config :rotor]
                    {:path "./log/jsk.log"
                     :max-size (* 512 1024)
                     :backlog 5}))




(defn- common-init []
  (log/info "Reading jsk-conf")
  (conf/init "conf/jsk-conf.clj")

  (log/info "Ensuring log directory exists at: " (conf/exec-log-dir))
  (ju/ensure-directory (conf/exec-log-dir))

  (n/init)
  (log/info "Notifications initialized."))

(defn run-as-web-app [port]
  (common-init)
  (jsk/init)
  (jetty/run-jetty #'jsk/app {:configurator jsk/ws-configurator
                              :port port
                              :join? false}))

(defn run-as-conductor [cmd-port status-port]
  (common-init)
  (conductor/init cmd-port status-port))

(defn run-as-agent [hostname cmd-port status-port]
  (common-init)
  (jagent/init hostname cmd-port status-port))


(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)
        {:keys [hostname status-port cmd-port web-app-port nrepl-port]} options]

    (init-logging)
    (log/info "JSK started with: " options)

    ;; Handle help and error conditions
    (cond
      (:help options) (exit 0 (usage summary))
      errors (exit 1 (error-msg errors)))

    (when nrepl-port
      (log/info "Starting the nRepl server...")
      (defonce nrepl-server (nrepl/start-server :port nrepl-port)))

    (case (:mode options)
      "agent" (run-as-agent hostname cmd-port status-port)
      "conductor" (run-as-conductor cmd-port status-port)
      "web" (run-as-web-app web-app-port)
      (exit 1 (usage summary)))))




