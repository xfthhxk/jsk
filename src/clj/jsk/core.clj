(ns jsk.core
  "JSK core"
  (:require [jsk.ps :as ps]
            [taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal spy with-log-level)]
            [clojurewerkz.quartzite.scheduler :as qs]
            [jsk.quartz :as q]))


(defn init [& args]
  (info "initailizing quartz")
  (qs/initialize)
  (qs/start)
  (let [date-job (q/make-shell-job "date")
        cal-job (q/make-shell-job "cal" [[1] [2012]])
        trigger (q/make-trigger "triggers.1")]
    (qs/schedule cal-job (q/make-trigger "triggers.1"))
    (qs/schedule date-job (q/make-trigger "triggers.2")))
  (info "quartz started"))

(defn shutdown []
  (info "quartz shutting down")
  (qs/shutdown))

 ; (let [date-job (make-shell-job "date")
 ;       cal-job (make-shell-job "cal" [[1] [2012]])
 ;       trigger (make-trigger "triggers.1")]
 ;   (qs/schedule cal-job (make-trigger "triggers.1"))
 ;   (qs/schedule date-job (make-trigger "triggers.2"))))

