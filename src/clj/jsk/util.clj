(ns jsk.util)

(defn validation-errors? [bouncer-result]
  (-> bouncer-result first nil? not))

(defn extract-validation-errors [bouncer-result]
  (first bouncer-result))

(defn make-error-response [errors]
  {:success? false :errors errors})


(def app-edn "application/edn")

(defn edn-request? [r]
  (let [ct (:content-type r)]
    (if ct
      (not= -1 (.indexOf ct app-edn))
      false)))


