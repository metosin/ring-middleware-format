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

   - :handle-error - ...
   - :formats - ...
   - :*format-name* - ...
     - :charset - ..."
  [handler & [{:keys [formats charset handle-error]
               :or {formats formatters
                    handle-error default-handle-error}
               :as opts}]]
  (->> formats
       (map (partial get-existing-formatter opts))
       (filter decoder?)
       ; Creating handler using reduce applies decoders in reverse order
       reverse
       (reduce
         (fn [handler {:keys [decoder decode?]}]
           (fn [{:keys [^InputStream body] :as req}]
             (try
               (if (and body (decode? req))
                 (let [^bytes byts (slurp-to-bytes (:body req))]
                   (if (> (count byts) 0)
                     (if-let [fmt-params (decoder (assoc req :body byts))]
                       (handler (assoc req
                                       :body-params fmt-params
                                       :params (merge (:params req) (if (map? fmt-params) fmt-params))
                                       :body (ByteArrayInputStream. byts)))
                       (handler req))
                     (handler req)))
                 (handler req))
               (catch Exception e
                 (handle-error e handler req)))))
         handler)))
