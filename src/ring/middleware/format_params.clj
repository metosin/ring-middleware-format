(ns ring.middleware.format-params
  (:require [ring.middleware.formatters :refer :all]
            [ring.middleware.format-utils :refer [slurp-to-bytes]])
  (:import [java.io InputStream ByteArrayInputStream]))

(set! *warn-on-reflection* true)

(defn default-handle-error
  "Default error handling function used, which rethrows the Exception"
  [e _ _]
  (throw e))

;;
;; Middleware
;;

(defn wrap-format-params
  "Wraps a handler such that requests body are deserialized from to
   the right format, added in a *:body-params* key and merged in *:params*.
   It takes 4 args:

 + **:formats** specifies a fn taking the body String as sole argument and
                giving back a hash-map.
 + **:charset** can be either a string representing a valid charset or a fn
                taking the req as argument and returning a valid charset.
 + **:handle-error** is a fn with a sig [exception handler request].
                     Return (handler obj) to continue executing a modified
                     request or directly a map to answer immediately. Defaults
                     to just rethrowing the Exception"
  [handler & [{:keys [formats charset handle-error]
               :or {formats formatters
                    handle-error default-handle-error}
               :as opts}]]
  (->> formats
       (map get-built-in-formatter)
       (filter decoder?)
       ; Creating handler using reduce applies decoders in reverse order
       reverse
       (reduce
         (fn [handler decoder]
           (let [decoder-fn (create-decoder decoder (get opts (:name decoder)))]
             (fn [{:keys [^InputStream body] :as req}]
               (try
                 (if (and body (decode? decoder req))
                   (let [^bytes byts (slurp-to-bytes (:body req))]
                     (if (> (count byts) 0)
                       (if-let [fmt-params (decoder-fn (assoc req :body byts))]
                         (handler (assoc req
                                         :body-params fmt-params
                                         :params (merge (:params req) (if (map? fmt-params) fmt-params))
                                         :body (ByteArrayInputStream. byts)))
                         (handler req))
                       (handler req)))
                   (handler req))
                 (catch Exception e
                   (handle-error e handler req))))))
         handler)))
