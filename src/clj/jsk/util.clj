(ns jsk.util)

(defn validation-errors? [bouncer-result]
  (-> bouncer-result first nil? not))

(defn extract-validation-errors [bouncer-result]
  (first bouncer-result))

(defn make-error-response [errors]
  {:success? false :errors errors})
