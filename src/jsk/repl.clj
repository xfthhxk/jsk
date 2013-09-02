(ns jsk.repl
  (:require [jsk.handler :as h]
            [ring.adapter.jetty :as jetty]
            [ring.server.standalone :as rs]
            [ring.middleware.file :as rmf]
            [ring.middleware.file-info :as rmfi]
            [taoensso.timbre :as timbre :refer (info warn error)]))

(defonce server (atom nil))

(defn get-handler []
  ;; #'app expands to (var app) so that when we reload ur code
  ;; the server is forced to re-resolve the symbol in the var
  ;; rather than having its own copy.  When the root binding
  ;; changes, the server picks it up without having to restart.
  (-> #'h/app

      (rmf/wrap-file "resources")  ; make static assets in $PROJECT_DIR/resources/public/ available
      (rmfi/wrap-file-info)))       ; Content-Type, Content-Length and Last Modified headers for files in body


(defn- really-start-server [port]
  (reset! server (jetty/run-jetty #'h/app {:port port :join? false :auto-reload? true}))
  (h/init)
  (info "Server started. Site is available at http://localhost:" port))

(defn- really-stop-server []
  (h/destroy)
  (.stop @server)
  (reset! server nil)
  (info "Server stopped."))

(defn start-server
  ([] (start-server 8080))
  ([port]
   (if (nil? @server)
     (really-start-server port)
     (info "Server is already running."))))

(defn stop-server []
  (if (nil? @server)
    (info "Server not started.")
    (really-stop-server)))
