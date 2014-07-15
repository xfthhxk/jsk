(defproject jsk "0.1.0-SNAPSHOT"
  :description "job scheduling"
  :url "http://jsk.io"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2268"]
                 [org.clojure/core.memoize "0.5.6"]
                 [org.clojure/core.async "0.1.303.0-886421-alpha"]
                 [org.clojure/tools.cli "0.3.1"]     ; command line option processing
                 [clojurewerkz/quartzite "1.2.0"]    ; clojure wrapper around quartz scheduling
                 [org.zeroturnaround/zt-exec "1.7"]  ; process execution
                 [com.taoensso/timbre "3.2.1"]       ; logging
                 [com.postspectacular/rotor "0.1.0"] ; rotating log file appender
                 [org.clojure/tools.nrepl "0.2.3"]
                 [bouncer "0.3.0"]            ; validation lib
                 [mysql/mysql-connector-java "5.1.31"]       ; mysql
                 ;; ;[com.h2database/h2 "1.3.174"]       ; embedded db
                 [korma "0.3.2"]                 ; sql dsl
                 [com.cemerick/friend "0.2.1"]       ; openid auth
                 [compojure "1.1.8"]                 ; routing library for ring
                 [com.keminglabs/jetty7-websockets-async "0.1.0"]
                 [ring "1.3.0"]
                 [fogus/ring-edn "0.2.0"]
                 [javax.mail/mail "1.4.7"
                   :exclusions [javax.activation/activation]]
                 ;; ;[enfocus "2.0.2"]
                 [enfocus "2.1.0"]
                 [jayq "2.5.1"]
                 [clj-time "0.7.0"]
                 [jnanomsg "0.2.0"]         ; messaging lib, updating to 0.3.1 causes compilation prob with clojurescript
                 ]
  :source-paths ["src/clj"]
  :profiles {:production {:ring {:open-browser? false :stacktraces? false :auto-reload? false}}
             :dev {:dependencies [[midje "1.6.3"]
                                  [ring-mock "0.1.5"]
                                  [ring/ring-devel "1.3.0"]]}}
  :plugins [[lein-ring "0.8.7"]
            [lein-typed "0.3.0"]
            [lein-cljsbuild "1.0.3"]]
  :ring {:handler jsk.console.handler/app
         :init jsk.console.handler/init
         :destroy jsk.console.handler/destroy
         :configurator jsk.handler/ws-configurator}
  :cljsbuild {:builds [{:source-paths ["src/cljs"]
                        :compiler {:output-to "resources/public/js/main.js"
                                   :optimizations :whitespace
                                   :pretty-print true}}]}
  ; :core.typed {:check [jsk.core jsk.handler jsk.ps jsk.repl jsk.quartz]}
  :min-lein-version "2.0.0"
  :main jsk.core
  :aot :all)
