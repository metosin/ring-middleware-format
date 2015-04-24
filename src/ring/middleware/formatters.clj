(ns ring.middleware.formatters
  (:require [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.tools.reader.edn :as edn]
            [cognitect.transit :as transit]
            [ring.middleware.format-utils :refer :all])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStream]
           [java.util.regex Pattern]
           [org.apache.commons.codec Decoder]))

;;
;; Protocols
;;

(defprotocol FormatDecoder
  "Implementations should have at-least the following fields:
  - name"
  (create-decoder [_ opts]
    "Should return a function which takes in request
    (String) and returns decoded body.")
  (decode? [_ req]
    "Should return true if given request can be decoded by this decoder."))

(defn decoder? [x]
  (satisfies? FormatDecoder x))

(defprotocol FormatEncoder
  "Implementations should have at-least the following fields:
  - name
  - content-type

  Following fields are added by the middleware:
  - encoder, created by calling create-encoder with options
  - enc-type, split content-type"
  (create-encoder [_ opts]
    "Should return a function which takes in body and request,
    and returns [body content-type], where body is
    encoded body as a ByteArray (or something which can be coerced
    to InputStream.)"))

(defn encoder? [x]
  (satisfies? FormatEncoder x))

(defn init-encoder [opts encoder]
  {:pre [(string? (:content-type encoder))]}
  (assoc encoder
         :encode-fn (create-encoder encoder (get opts (:name encoder)))
         :enc-type (first (parse-accept-header (:content-type encoder)))))

;;
;; Utils
;;

(defn regexp-predicate
  [^Pattern regexp req]
  (if-let [^String type (get-content-type req)]
    (and (:body req) (not (empty? (re-find regexp type))))))

(defn charset-decoder [decoder-fn {:keys [charset]
                                   :or {charset get-or-guess-charset}}]
  {:pre [(or (string? charset) (fn? charset))]}
  (fn [req]
    (let [^String char-enc (if (string? charset) charset (charset req))
          bstr (String. (:body req) char-enc)]
      (decoder-fn bstr))))

(defn charset-encoder [encode-fn content-type
                       {:keys [charset]
                        :or {charset default-charset-extractor}}]
  (fn [body req]
    (let [^String char-enc (if (string? charset) charset (charset req))
          ^String body-string (encode-fn body)
          body* (.getBytes body-string char-enc)
          ctype* (str content-type "; charset=" char-enc)]
      [body* ctype*])))

(defn binary-decoder [decoder-fn]
  (fn [req]
    (decoder-fn (ByteArrayInputStream. (:body req)))))

(defn binary-encoder [encode-fn content-type _]
  (fn [body _]
    (let [body* (encode-fn body)]
      [body* content-type])))

;;
;; JSON
;;

(defrecord JsonFormatter [name content-type kw?]
  FormatDecoder
  (create-decoder [_ opts]
    (charset-decoder
      #(json/parse-string % kw?)
      opts))
  (decode? [_ req]
    (regexp-predicate #"^application/(vnd.+)?json" req))

  FormatEncoder
  (create-encoder [_ {:keys [pretty] :as opts}]
    (charset-encoder #(json/generate-string % {:pretty pretty}) content-type opts)))

;;
;; EDN
;;

(defn- wrap-print-dup [handler]
  (fn [x]
    (binding [*print-dup* true]
      (handler x))))

(defrecord EdnFormatter [name content-type]
  FormatDecoder
  (create-decoder [_ opts]
    (charset-decoder
      (fn [#^String s]
        (when-not (.isEmpty (.trim s))
          (edn/read-string {:readers *data-readers*} s)))
      opts))
  (decode? [_ req]
    (regexp-predicate #"^application/(vnd.+)?(x-)?(clojure|edn)" req))

  FormatEncoder
  (create-encoder [_ {:keys [hf] :as opts}]
    (let [encode-fn (cond-> pr-str
                            hf wrap-print-dup)]
      (charset-encoder encode-fn content-type opts))))

; FIXME: Duplicate
(defrecord EdnEncoder [name content-type]
  FormatEncoder
  (create-encoder [_ {:keys [hf] :as opts}]
    (let [encode-fn (cond-> pr-str
                            hf wrap-print-dup)]
      (charset-encoder encode-fn content-type opts))))

;;
;; YAML
;;

(defn- wrap-html [handler]
  (fn [body]
    (str
      "<html>\n<head></head>\n<body><div><pre>\n"
      (handler body)
      "</pre></div></body></html>")))

(defrecord YamlFormatter [name content-type kw?]
  FormatDecoder
  (create-decoder [_ opts]
    (binding [yaml/*keywordize* kw?]
      (charset-decoder yaml/parse-string opts)))
  (decode? [_ req]
    (regexp-predicate  #"^(application|text)/(vnd.+)?(x-)?yaml" req))

  FormatEncoder
  (create-encoder [_ opts]
    (charset-encoder yaml/generate-string content-type opts)))

(defrecord YamlEncoder [name content-type html?]
  FormatEncoder
  (create-encoder [_ opts]
    (let [encode-fn (cond-> yaml/generate-string
                            html? wrap-html)]
      (charset-encoder encode-fn content-type opts))))

;;
;; Transit
;;

(defrecord TransitFormatter [name content-type fmt]
  FormatDecoder
  (create-decoder [_ opts]
    (binary-decoder
      (fn [in]
        (let [rdr (transit/reader in fmt (select-keys opts [:handlers :default-handler]))]
          (transit/read rdr)))))
  (decode? [_ req]
    (regexp-predicate
      (case fmt
       :json #"^application/(vnd.+)?(x-)?transit\+json"
       :msgpack #"^application/(vnd.+)?(x-)?transit\+msgpack")
      req))

  FormatEncoder
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
;; Formatter list
;;

(def formatters
  [(JsonFormatter.    :json            "application/json" false)
   (JsonFormatter.    :json-kw         "application/json" true)
   (EdnFormatter.     :edn             "application/edn")
   (EdnEncoder.       :clojure         "application/clojure")
   (YamlFormatter.    :yaml            "application/x-yaml" false)
   (YamlFormatter.    :yaml-kw         "application/x-yaml" true)
   (YamlEncoder.      :yaml-in-html    "text/html" true)
   (TransitFormatter. :transit-json    "application/transit+json" :json)
   (TransitFormatter. :transit-msgpack "application/transit+msgpack" :msgpack)])

(def formatters-map
  (into {} (map (juxt :name identity) formatters)))

(defn get-built-in-formatter [x]
  (if (keyword? x)
    (get formatters-map x)
    x))
