(ns ring.middleware.format-response
  (:require [cheshire.core :as json]
            [ring.util.response :as res]
            [clojure.java.io :as io]
            [clj-yaml.core :as yaml]
            [cognitect.transit :as transit]
            [ring.middleware.format-utils :refer [parse-accept-header default-charset-extractor]])
  (:import [java.io File InputStream ByteArrayOutputStream]))

(set! *warn-on-reflection* true)

(defn ^:no-doc serializable?
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
               (= (enc-type :sub-type) sub-type)))))

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
;; Encoders
;;

(defprotocol Encoder
  "Implementations should have at-least the following fields:
  - name
  - content-type

  Following fields are added by the middleware:
  - encoder, created by calling create-encoder with options
  - enc-type, split content-type"
  (create-encoder [_ options]
    "Should return a function which takes in body and request,
    and returns [body content-type], where body is
    encoded body as a ByteArray (or something which can be coerced
    to InputStream.)"))

(defn encoder? [x]
  (satisfies? Encoder x))

(defn binary-encoder [encode-fn content-type _]
  (fn [body _]
    (let [body* (encode-fn body)]
      [body* content-type])))

(defn charset-encoder [encode-fn content-type
                       {:keys [charset]
                        :or {charset default-charset-extractor}}]
  (fn [body req]
    (let [^String char-enc (if (string? charset) charset (charset req))
          ^String body-string (encode-fn body)
          body* (.getBytes body-string char-enc)
          ctype* (str content-type "; charset=" char-enc)]
      [body* ctype*])))

;;
;; JSON
;;

(defrecord JsonEncoder [name content-type]
  Encoder
  (create-encoder [_ {:keys [pretty] :as opts}]
    (let [encode-fn (if pretty
                      #(json/generate-string % {:pretty pretty})
                      json/generate-string)]
      (charset-encoder encode-fn content-type opts))))

;;
;; Clojure / EDN
;;

(defn- wrap-print-dup [handler]
  (fn [x]
    (binding [*print-dup* true]
      (handler x))))

(defrecord ClojureEncoder [name content-type]
  Encoder
  (create-encoder [_ {:keys [hf] :as opts}]
    (let [encode-fn (cond-> pr-str
                            hf wrap-print-dup)]
      (charset-encoder encode-fn content-type opts))))

;;
;; Yaml
;;

(defn- wrap-html [handler]
  (fn [body]
    (str
      "<html>\n<head></head>\n<body><div><pre>\n"
      (handler body)
      "</pre></div></body></html>")))

(defrecord YamlEncoder [name content-type html?]
  Encoder
  (create-encoder [_ opts]
    (let [encode-fn (cond-> yaml/generate-string
                            html? wrap-html)]
      (charset-encoder encode-fn content-type opts))))

;;
;; Transit
;;

(defrecord TransitEncoder [name content-type fmt]
  Encoder
  (create-encoder [_ {:keys [verbose] :as opts}]
    (binary-encoder
      (fn [data]
        (let [out (ByteArrayOutputStream.)
              full-fmt (if (and (= fmt :json) verbose)
                         :json-verbose
                         fmt)
              wrt (transit/writer out full-fmt (select-keys opts [:handlers]))]
          (transit/write wrt data)
          (.toByteArray out)))
      content-type
      opts)))

;;
;; Encoder list
;;

(def format-encoders
  [(JsonEncoder. :json "application/json")
   (JsonEncoder. :json-kw "application/json")
   (ClojureEncoder. :edn "application/edn")
   (ClojureEncoder. :clojure "application/clojure")
   (YamlEncoder. :yaml "application/x-yaml" false)
   (YamlEncoder. :yaml-kw "application/x-yaml" false)
   (YamlEncoder. :yaml-in-html "text/html" true)
   (TransitEncoder. :transit-json "application/transit+json" :json)
   (TransitEncoder. :transit-msgpack "application/transit+msgpack" :msgpack)])

(def format-encoders-map
  (into {} (map (juxt :name identity) format-encoders)))

;;
;; Middlewares
;;

(defn wrap-response
  "Wraps a handler such that responses body to requests are formatted to
  the right format. If no *Accept* header is found, use the first encoder.

 + **:encoders** list encoders, either Encoder instance of a keyword
                 referring built-in Encoder
 + **:predicate-fn** is a predicate taking the request and response as
                     arguments to test if serialization should be used
 + **:charset** can be either a string representing a valid charset or a fn
                taking the req as argument and returning a valid charset
                (*utf-8* is strongly suggested)
 + **:handle-error** is a fn with a sig [exception request response]. Defaults
                     to just rethrowing the Exception"
  [handler & [{:keys [encoders predicate-fn handle-error]
               :or {encoders format-encoders
                    predicate-fn serializable?
                    handle-error default-handle-error}
               :as opts}]]
  (let [encoders (for [encoder encoders
                       :let [encoder (if (keyword? encoder)
                                       (get format-encoders-map encoder)
                                       encoder)]
                       :when (encoder? encoder)]
                   (assoc encoder :encoder (create-encoder encoder (get opts (if (keyword? encoder) encoder (:name encoder))))
                                  :enc-type (first (parse-accept-header (:content-type encoder)))))]
    (fn [req]
      (let [{:keys [body] :as response} (handler req)]
        (try
          (if (predicate-fn req response)
            (let [{:keys [encoder]} (or (preferred-encoder encoders req) (first encoders))
                  [body* content-type] (encoder body req)
                  body-length (count body*)]
              (-> response
                  (assoc :body (io/input-stream body*))
                  (res/content-type content-type)
                  (res/header "Content-Length" body-length)))
            response)
          (catch Exception e
            (handle-error e req response)))))))
