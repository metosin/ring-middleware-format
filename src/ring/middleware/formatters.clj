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

(defn make-formatter
  "Creates an instance of formatter.

   "
  [{:keys [content-type decoder decode? encoder]}]
  {:pre [(string? content-type)
         (if decoder (and (fn? decoder) (fn? decode?)) true)
         (if encoder (fn? encoder) true)]}
  {:content-type content-type
   :enc-type (first (parse-accept-header content-type))
   :decoder decoder
   :decode? decode?
   :encoder encoder})

(defn decoder? [x]
  (boolean (:decoder x)))

(defn encoder? [x]
  (boolean (:encoder x)))

(defmulti create-formatter (fn [k opts] k))

;;
;; Utils
;;

(defn regexp-predicate
  [^Pattern regexp]
  (fn [req]
    (if-let [^String type (get-content-type req)]
      (and (:body req) (not (empty? (re-find regexp type)))))))

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

(defn json-formatter
  "JSON Formatter.
   Uses cheshire.

   Available options:
   - :kw - Passed to cheshire as key-fn parameter. If true,
   property names will be keywordizes. Can be a function.
   - :pretty? - If true, the output will be pretty printed.

   By default registered with following keys:
   - :json
   - :json-kw - Sets the :kw option to true."
  [content-type {:keys [kw pretty?] :as opts}]
  (make-formatter
    {:content-type content-type
     :decoder (charset-decoder
                #(json/parse-string % kw)
                opts)
     :decode? (regexp-predicate #"^application/(vnd.+)?json")
     :encoder (charset-encoder #(json/generate-string % {:pretty pretty?}) content-type opts)}))

(defmethod create-formatter :json [_ opts]
  (json-formatter "application/json" opts))

(defmethod create-formatter :json-kw [_ opts]
  (json-formatter "application/json" (assoc opts :kw true)))

;;
;; EDN
;;

(defn- wrap-print-dup [handler]
  (fn [x]
    (binding [*print-dup* true]
      (handler x))))

(defn edn-formatter
  "EDN Formatter.
   Uses clojure.tools.reader.edn.

   Available options:
   - :hf? - If true, sets *print-dup* to true.

   Registered with following keys:
   - :edn
   - :clojure - Read only format with \"application/clojure\" content-type."
  [content-type {:keys [hf?] :as opts}]
  (make-formatter
    {:content-type content-type
     :decoder (charset-decoder
                (fn [#^String s]
                  (when-not (.isEmpty (.trim s))
                    (edn/read-string {:readers *data-readers*} s)))
                opts)
     :decode? (regexp-predicate #"^application/(vnd.+)?(x-)?(clojure|edn)")

     :encoder (let [encode-fn (cond-> pr-str
                                hf? wrap-print-dup)]
                (charset-encoder encode-fn content-type opts))}))

(defmethod create-formatter :edn [_ opts]
  (edn-formatter "application/edn" opts))

(defmethod create-formatter :clojure [_ opts]
  (-> (edn-formatter "application/clojure" opts)
      (dissoc :decoder)))

;;
;; YAML
;;

(defn- wrap-html [handler]
  (fn [body]
    (str
      "<html>\n<head></head>\n<body><div><pre>\n"
      (handler body)
      "</pre></div></body></html>")))

(defn yaml-formatter
  "YAML Formatter.

   Available options
   - :kw? - If true, property names are converted to keywords.
   - :html? - If true, output is wrapped in html document.

   Registered with following keys:
   - :yaml
   - :yaml-kw - Sets the :kw? option to true.
   - :yaml-in-html - Read only format with content-type \"text/html\". Sets :html? to true."
  [content-type {:keys [html? kw?] :as opts}]
  (make-formatter
    {:content-type content-type
     :decoder (binding [yaml/*keywordize* kw?]
                (charset-decoder yaml/parse-string opts))
     :decode? (regexp-predicate  #"^(application|text)/(vnd.+)?(x-)?yaml")
     :encoder (charset-encoder (cond-> yaml/generate-string html? wrap-html) content-type opts)}))

(defmethod create-formatter :yaml [_ opts]
  (yaml-formatter "application/x-yaml" opts))

(defmethod create-formatter :yaml-kw [_ opts]
  (yaml-formatter "application/x-yaml" (assoc opts :kw? true)))

(defmethod create-formatter :yaml-in-html [_ opts]
  (-> (yaml-formatter "text/html" (assoc opts :html? true))
      (dissoc :decoder)))

;;
;; Transit
;;

(defn transit-formatter
  "Transit formatter.

   Available options:
   - :reader-opts - passed to transit/reader
     - :handlers
     - :default-handler
   - :writer-opts - passed to transit/writer
     - :handlers

   Registered with following keys:
   - :transit-json
   - :transit-msgpack

   Check http://cognitect.github.io/transit-clj/ for more info."
  [content-type fmt {:keys [verbose reader-opts writer-opts] :as opts}]
  (make-formatter
    {:content-type content-type
     :decoder (binary-decoder
                (fn [in]
                  (let [rdr (transit/reader in fmt reader-opts)]
                    (transit/read rdr))))
     :decode? (regexp-predicate
                (case fmt
                  :json #"^application/(vnd.+)?(x-)?transit\+json"
                  :msgpack #"^application/(vnd.+)?(x-)?transit\+msgpack"))
     :encoder (binary-encoder
                (fn [data]
                  (let [out (ByteArrayOutputStream.)
                        full-fmt (if (and (= fmt :json) verbose)
                                   :json-verbose
                                   fmt)
                        wrt (transit/writer out full-fmt writer-opts)]
                    (transit/write wrt data)
                    (.toByteArray out)))
                content-type
                opts)}))

(defmethod create-formatter :transit-json [_ opts]
  (transit-formatter "application/transit+json" :json opts))

(defmethod create-formatter :transit-msgpack [_ opts]
  (transit-formatter "application/transit+msgpack" :msgpack opts))

;;
;; Utils
;;

(def formatters [:json :json-kw :yaml :yaml-kw :yaml-in-html :edn :clojure :transit-json :transit-msgpack])

(defn get-existing-formatter [opts k]
  (if (keyword? k)
    (create-formatter k (get opts k))
    k))
