;; Anything you type in here will be executed
;; immediately with the results shown on the
;; right.

(neg? 4)


(merge {:hello 2 :bye 3} {:hello 1})

(assoc {:a 1} :c 2)

(read-string "{:x 5}")

(pr-str 1)


; curl -H "Content-Type: application/edn" -d '{:schedule-id "9", :schedule-name "oh wow mary", :schedule-desc "my new schedule desc upd", :cron-expression "anthr cron updaa", :blah "Submit"}' http://localhost:8080/schedules/save
{:schedule-id 9  :schedule-name "oh wow mary" :schedule-desc "my new schedule desc upd" :cron-expression "google me"}
((constantly false))

(apply str [1 2 3 " how are you"])

(count "")

(-> "" count zero?)

(read-string "{42 :hello}")

(dissoc {42 :hello} 42)

(def request {:uri "/" :name "blah" :session nil} )

(update-in request [:uri]
           #(if (= "/" %1) "/index.html" %1))

(defrecord Schedule [id nm desc cron-expr])

(merge (Schedule. nil nil nil nil) {:id 42 :cron-expr "* * 5 *" })


(use 'swiss-arrows.core)

(-<> 42 {:key <> :val "something"})


(flatten {:42 "a" :23 "b"})
(flatten [42 [32 [3]]])

(update-in request [:session] assoc :user-id 42 )

request

(dissoc {:a 42} :b)

