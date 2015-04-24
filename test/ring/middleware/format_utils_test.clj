(ns ring.middleware.format-utils-test
  (:require [clojure.test :refer :all]
            [ring.middleware.format-utils :refer :all]))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Content-Type parsing ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest orders-values-correctly
  (let [accept "text/plain, */*, text/plain;level=1, text/*, text/*;q=0.1"]
    (is (= (parse-accept-header accept)
           (list {:type "text"
                  :sub-type "plain"
                  :parameter "level=1"
                  :q 1.0}
                 {:type "text"
                  :sub-type "plain"
                  :q 1.0}
                 {:type "text"
                  :sub-type "*"
                  :q 1.0}
                 {:type "*"
                  :sub-type "*"
                  :q 1.0}
                 {:type "text"
                  :sub-type "*"
                  :q 0.1})))))
