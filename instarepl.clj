;; Anything you type in here will be executed
;; immediately with the results shown on the
;; right.

(load "jsk/repl")
(load "jsk/core")
(load "jsk/views/jobs")

(jsk.repl/start-server)
(jsk.repl/stop-server)


(require '[net.cgrand.enlive-html :as e])

(def nodes (e/html-resource "jsk/views/jobs.html"))


(e/select nodes [:table :tr.job-name-row])


(e/select nodes [(e/attr= :class "job-name")])

(e/select nodes [:#page-created-ts])

(e/transform nodes [:#page-created-ts] identity)
(e/transform nodes [:#page-created-ts] (fn [n]  (assoc n :content (str (java.util.Date.)))))

(apply str (e/emit* (e/transform nodes [:#page-created-ts] (e/append (str (java.util.Date.))))))

(neg? 4)

(clojure.string/lower-case "hEllO")

(clojure.string/replace "schedule_id_name" "_" "-")

(merge {:hello 2 :bye 3} {:hello 1})

