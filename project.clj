(defproject jsk "0.1.0-SNAPSHOT"
  :description "job scheduling"
  :url "http://jsk.io"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [clojurewerkz/quartzite "1.1.0"]    ; clojure wrapper around quartz scheduling
                 [org.zeroturnaround/zt-exec "1.4"]  ; process execution
                 [com.taoensso/timbre "2.6.1"]       ; logging
                 [com.postspectacular/rotor "0.1.0"] ; rotating log file appender
                 [lib-noir "0.6.8"]
                 [enlive "1.1.4"]                    ; templating library
                 [compojure "1.1.5"]                 ; routing library for ring
                 [ring-server "0.2.8"]]
  :source-paths ["src"]
  :ring {:handler jsk.handler/war-handler
         :init jsk.handler/init
         :destroy jsk.handler/destroy}
  :profiles {:production {:ring {:open-browser? false :stacktraces? false :auto-reload? false}}
             :dev {:ring {:port 8080 :nrepl {:start? true :port 8081}}
                   :dependencies [[midje "1.5.1"]
                                  [ring-mock "0.1.5"]
                                  [ring/ring-devel "1.1.8"]]}}
  :plugins [[lein-ring "0.8.7"]]
  :min-lein-version "2.0.0")
