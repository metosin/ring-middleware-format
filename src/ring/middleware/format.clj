(ns ring.middleware.format
  (:require [ring.middleware.format-params :as par]
            [ring.middleware.format-response :as res]))

(defn wrap-formats
  [handler & [opts]]
  (-> handler
      (par/wrap-format-params opts)
      (res/wrap-format-response opts)))
