(ns ring.middleware.format-response
  (:require [ring.util.response :as res]
            [clojure.java.io :as io]
            [ring.middleware.formatters :refer :all]
            [ring.middleware.format-utils :refer [parse-accept-header]])
  (:import [java.io File InputStream]))

(set! *warn-on-reflection* true)

(defn serializable?
  "Predicate that returns true whenever the response body is non-nil, and not a
  String, File or InputStream."
  [_ {:keys [body] :as response}]
  (when response
    (not (or
           (nil? body)
           (string? body)
           (instance? File body)
           (instance? InputStream body)))))

(defn can-encode?
  "Check whether encoder can encode to accepted-type.
  Accepted-type should have keys *:type* and *:sub-type* with appropriate
  values."
  [{:keys [enc-type]} {:keys [type sub-type] :as accepted-type}]
  (or (= "*" type)
      (and (= (:type enc-type) type)
           (or (= "*" sub-type)
               (= (:sub-type enc-type) sub-type)))))

(defn preferred-encoder
  "Return the encoder that encodes to the most preferred type.
  If the *Accept* header of the request is a *String*, assume it is
  according to Ring spec. Else assume the header is a sequence of
  accepted types sorted by their preference. If no accepted encoder is
  found, return *nil*. If no *Accept* header is found, return the first
  encoder."
  [encoders req]
  (if-let [accept (get-in req [:headers "accept"] (:content-type req))]
    (first (for [accepted-type (if (string? accept)
                                 (parse-accept-header accept)
                                 accept)
                 encoder encoders
                 :when (can-encode? encoder accepted-type)]
             encoder))
    (first encoders)))

(defn default-handle-error
  "Default error handling function used, which rethrows the Exception"
  [e _ _]
  (throw e))

;;
;; Middlewares
;;

(defn wrap-format-response
  "Wraps a handler such that responses body to requests are formatted to
   the right format. If no *Accept* header is found, use the first encoder.

   - :predicate - ...
   - :handle-error - ...
   - :formats - ...
   - :*format-name* - ...
     - :charset - ..."
  [handler & [{:keys [formats predicate handle-error]
               :or {formats formatters
                    predicate serializable?
                    handle-error default-handle-error}
               :as opts}]]
  (let [encoders (->> formats
                      (map (partial get-existing-formatter opts))
                      (filter encoder?))]
    (assert (seq encoders))
    (fn [req]
      (let [{:keys [body] :as response} (handler req)]
        (try
          (if (predicate req response)
            (let [{:keys [encoder]} (or (preferred-encoder encoders req) (first encoders))
                  [body* content-type] (encoder body req)]
              (-> response
                  (assoc :body (io/input-stream body*))
                  (res/content-type content-type)
                  (res/header "Content-Length" (count body*))))
            response)
          (catch Exception e
            (handle-error e req response)))))))
