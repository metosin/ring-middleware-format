(ns ring.middleware.format-params
  (:require [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.tools.reader.edn :as edn]
            [cognitect.transit :as transit]
            [ring.middleware.format-utils :refer [slurp-to-bytes get-content-type get-or-guess-charset]])
  (:import [java.io ByteArrayInputStream InputStream]
           [java.util.regex Pattern]
           [org.apache.commons.codec Decoder]))

(set! *warn-on-reflection* true)

(defn default-handle-error
  "Default error handling function used, which rethrows the Exception"
  [e _ _]
  (throw e))

(defn regexp-predicate
  [^Pattern regexp, req]
  (if-let [^String type (get-content-type req)]
    (and (:body req) (not (empty? (re-find regexp type))))))

(defn charset-decoder [decoder-fn {:keys [charset] :as opts}]
  {:pre [(or (string? charset) (fn? charset))]}
  (fn [req]
    (let [^bytes byts (slurp-to-bytes (:body req))]
      (if (> (count byts) 0)
        (let [^String char-enc (if (string? charset) charset (charset (assoc req :body byts)))
              bstr (String. byts char-enc)]
          (decoder-fn bstr))))))

(defn binary-decoder [decoder-fn]
  (fn [req]
    (decoder-fn (:body req))))

;;
;; Decoders
;;

(defprotocol FormatDecoder
  "Implementations should have at-least the following fields:
  - name"
  (create-decoder [_ options]
    "Should return a function which takes in request
    (String) and returns decoded body.")
  (predicate [_ req]
    "Should return true if given request can be decoded by this decoder."))

(defn decoder? [x]
  (satisfies? FormatDecoder x))

;;
;; JSON
;;

(defrecord JsonDecoder [name kw?]
  FormatDecoder
  (create-decoder [_ opts]
    (charset-decoder
      (if kw?
        #(json/parse-string % true)
        json/parse-string)
      opts))
  (predicate [_ req]
    (regexp-predicate #"^application/(vnd.+)?json" req)))

;;
;; EDN
;;

(defrecord EdnDecoder [name]
  FormatDecoder
  (create-decoder [_ opts]
    (charset-decoder
      (fn [#^String s]
        (when-not (.isEmpty (.trim s))
          (edn/read-string {:readers *data-readers*} s)))
      opts))
  (predicate [_ req]
    (regexp-predicate #"^application/(vnd.+)?(x-)?(clojure|edn)" req)))

;;
;; YAML
;;

(defrecord YamlDecoder [name kw?]
  FormatDecoder
  (create-decoder [_ opts]
    (binding [yaml/*keywordize* kw?]
      (charset-decoder yaml/parse-string opts)))
  (predicate [_ req]
    (regexp-predicate  #"^(application|text)/(vnd.+)?(x-)?yaml" req)))

;;
;; Transit
;;

(defrecord TransitDecoder [name fmt]
  FormatDecoder
  (create-decoder [_ opts]
    (binary-decoder
      (fn [in]
        (let [rdr (transit/reader in fmt (select-keys opts [:handlers :default-handler]))]
          (transit/read rdr)))))
  (predicate [_ req]
    (regexp-predicate
      (case fmt
       :json #"^application/(vnd.+)?(x-)?transit\+json"
       :msgpack #"^application/(vnd.+)?(x-)?transit\+msgpack")
      req)))

;;
;; Decoder list
;;

(def format-decoders
  [(JsonDecoder. :json false)
   (JsonDecoder. :json-kw true)
   (EdnDecoder. :edn)
   (YamlDecoder. :yaml false)
   (YamlDecoder. :yaml-kw true)
   (TransitDecoder. :transit-json :json)
   (TransitDecoder. :transit-msgpack :msgpack)])

(def format-decoders-map
  (into {} (map (juxt :name identity) format-decoders)))

;;
;; Middleware
;;

(defn wrap-params
  "Wraps a handler such that requests body are deserialized from to
   the right format, added in a *:body-params* key and merged in *:params*.
   It takes 4 args:

 + **:decoders** specifies a fn taking the body String as sole argument and
                giving back a hash-map.
 + **:charset** can be either a string representing a valid charset or a fn
                taking the req as argument and returning a valid charset.
 + **:handle-error** is a fn with a sig [exception handler request].
                     Return (handler obj) to continue executing a modified
                     request or directly a map to answer immediately. Defaults
                     to just rethrowing the Exception"
  [handler & [{:keys [decoders charset handle-error]
               :or {decoders format-decoders
                    handle-error default-handle-error}
               :as opts}]]
  (let [opts (merge {:charset get-or-guess-charset} opts)]
    (reduce
      (fn [handler decoder]
        (let [decoder (if (keyword? decoder) (get format-decoders-map decoder) decoder)
              decoder-fn (create-decoder decoder (merge opts (get opts (:name decoder))))]
          (fn [{:keys [^InputStream body] :as req}]
            (try
              (if (and body (predicate decoder req))
                (if-let [fmt-params (decoder-fn req)]
                  (handler (assoc req
                                  :body-params fmt-params
                                  :params (merge (:params req)
                                                 (when (map? fmt-params) fmt-params))
                                  ;; FIXME: body is now empty?
                                  ))
                  (handler req))
                (handler req))
              (catch Exception e
                (handle-error e handler req))))))
      handler decoders)))
