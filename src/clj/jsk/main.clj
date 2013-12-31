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


(defn run-as-web-app [port]
  (jsk/init)
  (jetty/run-jetty #'jsk/app {:configurator jsk/ws-configurator
                              :port port
                              :join? false}))


(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)
        {:keys [hostname status-port cmd-port web-app-port]} options]


    ;; Handle help and error conditions
    (cond
      (:help options) (exit 0 (usage summary))
      errors (exit 1 (error-msg errors)))

    (init-logging)
    (log/info "JSK started with: " options)

    (conf/init "conf/jsk-conf.clj")
    (log/info "Ensuring log directory exists at: " (conf/exec-log-dir))
    (ju/ensure-directory (conf/exec-log-dir))

    (n/init)
    (log/info "Notifications initialized.")

    (case (:mode options)
      "agent" (jagent/init hostname cmd-port status-port)
      "conductor" (conductor/init cmd-port status-port)
      "web" (run-as-web-app web-app-port)
      (exit 1 (usage summary)))))




