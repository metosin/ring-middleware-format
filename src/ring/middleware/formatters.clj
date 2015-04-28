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

(defn make-formatter [{:keys [name content-type decoder decode? encoder]}]
  {:pre [(keyword? name) (string? content-type)
         (or (not decoder) (fn? decoder))
         (or (not encoder) (fn? encoder))]}
  {:name name
   :content-type content-type
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

(defn json-formatter [name content-type {:keys [kw? pretty?] :as opts}]
  (make-formatter
    {:name name
     :content-type content-type
     :decoder (charset-decoder
                #(json/parse-string % kw?)
                opts)
     :decode? (regexp-predicate #"^application/(vnd.+)?json")
     :encoder (charset-encoder #(json/generate-string % {:pretty pretty?}) content-type opts)}))

(defmethod create-formatter :json [k opts]
  (json-formatter k "application/json" opts))

(defmethod create-formatter :json-kw [k opts]
  (json-formatter k "application/json" (assoc opts :kw? true)))

;;
;; EDN
;;

(defn- wrap-print-dup [handler]
  (fn [x]
    (binding [*print-dup* true]
      (handler x))))

(defn edn-formatter [name content-type {:keys [hf] :as opts}]
  (make-formatter
    {:name name
     :content-type content-type
     :decoder (charset-decoder
                (fn [#^String s]
                  (when-not (.isEmpty (.trim s))
                    (edn/read-string {:readers *data-readers*} s)))
                opts)
     :decode? (regexp-predicate #"^application/(vnd.+)?(x-)?(clojure|edn)")

     :encoder (let [encode-fn (cond-> pr-str
                                hf wrap-print-dup)]
                (charset-encoder encode-fn content-type opts))}))

(defmethod create-formatter :edn [k opts]
  (edn-formatter k "application/edn" opts))

(defmethod create-formatter :clojure [k opts]
  (-> (edn-formatter k "application/clojure" opts)
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

(defn yaml-formatter [name content-type {:keys [html? kw?] :as opts}]
  (make-formatter
    {:name name
     :content-type content-type
     :decoder (binding [yaml/*keywordize* kw?]
                (charset-decoder yaml/parse-string opts))
     :decode? (regexp-predicate  #"^(application|text)/(vnd.+)?(x-)?yaml")
     :encoder (charset-encoder (cond-> yaml/generate-string html? wrap-html) content-type opts)}))

(defmethod create-formatter :yaml [k opts]
  (yaml-formatter k "application/x-yaml" opts))

(defmethod create-formatter :yaml-kw [k opts]
  (yaml-formatter k "application/x-yaml" (assoc opts :kw? true)))

(defmethod create-formatter :yaml-in-html [k opts]
  (-> (yaml-formatter k "text/html" (assoc opts :html? true))
      (dissoc :decoder)))

;;
;; Transit
;;

(defn transit-formatter [name content-type fmt {:keys [verbose] :as opts}]
  (make-formatter
    {:name name
     :content-type content-type
     :decoder (binary-decoder
                (fn [in]
                  (let [rdr (transit/reader in fmt (select-keys opts [:handlers :default-handler]))]
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
                        wrt (transit/writer out full-fmt (select-keys opts [:handlers]))]
                    (transit/write wrt data)
                    (.toByteArray out)))
                content-type
                opts)}))

(defmethod create-formatter :transit-json [k opts]
  (transit-formatter k "application/transit+json" :json opts))

(defmethod create-formatter :transit-msgpack [k opts]
  (transit-formatter k "application/transit+msgpack" :msgpack opts))

;;
;; Utils
;;

(def formatters [:json :json-kw :yaml :yaml-kw :yaml-in-html :edn :clojure :transit-json :transit-msgpack])

(defn get-existing-formatter [opts k]
  (if (keyword? k)
    (create-formatter k (get opts k))
    k))
