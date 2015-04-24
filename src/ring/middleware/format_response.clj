(ns ring.middleware.format-response
  (:require [cheshire.core :as json]
            [ring.util.response :as res]
            [clojure.java.io :as io]
            [clj-yaml.core :as yaml]
            [clojure.string :as s]
            [cognitect.transit :as transit])
  (:use [clojure.core.memoize :only [lu]])
  (:import [java.io File InputStream BufferedInputStream
            ByteArrayOutputStream]
           [java.nio.charset Charset]))

(set! *warn-on-reflection* true)

(def available-charsets
  "Set of recognised charsets by the current JVM"
  (into #{} (map s/lower-case (.keySet (Charset/availableCharsets)))))

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
  [{:keys [enc-type] :as encoder} {:keys [type sub-type] :as accepted-type}]
  (or (= "*" type)
      (and (= (:type enc-type) type)
           (or (= "*" sub-type)
               (= (enc-type :sub-type) sub-type)))))

(defn ^:no-doc sort-by-check
  [by check headers]
  (sort-by by (fn [a b]
                (cond (= (= a check) (= b check)) 0
                      (= a check) 1
                      :else -1))
           headers))

(defn parse-accept-header*
  "Parse Accept headers into a sorted sequence of maps.
  \"application/json;level=1;q=0.4\"
  => ({:type \"application\" :sub-type \"json\"
       :q 0.4 :parameter \"level=1\"})"
  [accept-header]
  (->> (map (fn [val]
              (let [[media-range & rest] (s/split (s/trim val) #";")
                    type (zipmap [:type :sub-type]
                                 (s/split (s/trim media-range) #"/"))]
                (cond (nil? rest)
                      (assoc type :q 1.0)
                      (= (first (s/triml (first rest)))
                         \q) ;no media-range params
                      (assoc type :q
                             (Double/parseDouble
                              (second (s/split (first rest) #"="))))
                      :else
                      (assoc (if-let [q-val (second rest)]
                               (assoc type :q
                                      (Double/parseDouble
                                       (second (s/split q-val #"="))))
                               (assoc type :q 1.0))
                        :parameter (s/trim (first rest))))))
            (s/split accept-header #","))
       (sort-by-check :parameter nil)
       (sort-by-check :type "*")
       (sort-by-check :sub-type "*")
       (sort-by :q >)))

(def parse-accept-header
  "Memoized form of [[parse-accept-header*]]"
  (lu parse-accept-header* {} :lu/threshold 500))

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

(defn parse-charset-accepted
  "Parses an *accept-charset* string to a list of [*charset* *quality-score*]"
  [v]
  (let [segments (s/split v #",")
        choices (for [segment segments
                      :when (not (empty? segment))
                      :let [[_ charset qs] (re-find #"([^;]+)(?:;\s*q\s*=\s*([0-9\.]+))?" segment)]
                      :when charset
                      :let [qscore (try
                                     (Double/parseDouble (s/trim qs))
                                     (catch Exception e 1))]]
                  [(s/trim charset) qscore])]
    choices))

(defn preferred-charset
  "Returns an acceptable choice from a list of [*charset* *quality-score*]"
  [charsets]
  (or
   (->> (sort-by second charsets)
        (filter (comp available-charsets first))
        (first)
        (first))
   "utf-8"))

(defn make-encoder
  "Return a encoder map suitable for [[wrap-format-response.]]
   f takes a string and returns an encoded string
   type *Content-Type* of the encoded string
   (make-encoder json/generate-string \"application/json\")"
  ([encoder-fn content-type binary?]
     {:encoder-fn encoder-fn
      :enc-type (first (parse-accept-header content-type))
      :binary? binary?})
  ([encoder-fn content-type]
     (make-encoder encoder-fn content-type false)))

;;
;; Encoders
;;

(defn make-json-encoder [{:keys [pretty]}]
  (if pretty
    #(json/generate-string % {:pretty pretty})
    json/generate-string))

(defn ^:no-doc make-clojure-encoder
  [{:keys [hf]}]
  (binding [*print-dup* (boolean hf)]
    (fn [struct]
      (pr-str struct))))

(defn- wrap-html [handler]
  (fn [body]
    (str
      "<html>\n<head></head>\n<body><div><pre>\n"
      (handler body)
      "</pre></div></body></html>")))

(defn ^:no-doc make-yaml-encoder
  [html _]
  (cond-> yaml/generate-string
          html wrap-html))

(defn ^:no-doc make-transit-encoder
  [fmt {:keys [verbose] :as options}]
  (fn [data]
    (let [out (ByteArrayOutputStream.)
          full-fmt (if (and (= fmt :json) verbose)
                     :json-verbose
                     fmt)
          wrt (transit/writer out full-fmt options)]
      (transit/write wrt data)
      (.toByteArray out))))

(def ^:no-doc format-encoders
  (array-map
    :json            (make-encoder make-json-encoder "application/json")
    :json-kw         (make-encoder make-json-encoder "application/json")
    :edn             (make-encoder make-clojure-encoder "application/edn")
    :clojure         (make-encoder make-clojure-encoder "application/clojure")
    :yaml            (make-encoder (partial make-yaml-encoder false) "application/x-yaml")
    :yaml-kw         (make-encoder (partial make-yaml-encoder false) "application/x-yaml")
    :yaml-in-html    (make-encoder (partial make-yaml-encoder true) "text/html")
    :transit-json    (make-encoder (partial make-transit-encoder :json) "application/transit+json" :binary)
    :transit-msgpack (make-encoder (partial make-transit-encoder :msgpack) "application/transit+msgpack" :binary)))

;;
;; Utils
;;

(defn default-handle-error
  "Default error handling function used, which rethrows the Exception"
  [e _ _]
  (throw e))

(defn choose-charset*
  "Returns an useful charset from the accept-charset string.
   Defaults to utf-8"
  [accept-charset]
  (let [possible-charsets (parse-charset-accepted accept-charset)]
      (preferred-charset possible-charsets)))

(def choose-charset
  "Memoized form of [[choose-charset*]]"
  (lu choose-charset* {} :lu/threshold 500))

(defn default-charset-extractor
  "Default charset extractor, which returns either *Accept-Charset*
   header field or *utf-8*"
  [request]
  (if-let [accept-charset (get-in request [:headers "accept-charset"])]
    (choose-charset accept-charset)
    "utf-8"))

;;
;; Middlewares
;;

(defn wrap-response
  "Wraps a handler such that responses body to requests are formatted to
  the right format. If no *Accept* header is found, use the first encoder.

 + **:formats** list of either keywords of supported encoders
                (in ring-middleware-format.format-response/format-encoders)
                or encoders
 + **:predicate** is a predicate taking the request and response as
                  arguments to test if serialization should be used
 + **:charset** can be either a string representing a valid charset or a fn
                taking the req as argument and returning a valid charset
                (*utf-8* is strongly suggested)
 + **:handle-error** is a fn with a sig [exception request response]. Defaults
                     to just rethrowing the Exception"
  [handler & [{:keys [formats predicate charset handle-error]
               :or {formats (keys format-encoders)
                    predicate serializable?
                    handle-error default-handle-error
                    charset default-charset-extractor}
               :as opts}]]
  (let [encoders (for [format formats
                       :when format
                       :let [encoder (if (map? format)
                                       format
                                       (get format-encoders (keyword format)))
                             encoder-fn (:encoder-fn encoder)]
                       :when encoder]
                   (assoc encoder :encoder (encoder-fn (get opts format))))]
    (fn [req]
      (let [{:keys [body] :as response} (handler req)]
        (try
          (if (predicate req response)
            (let [{:keys [encoder enc-type binary?]} (or (preferred-encoder encoders req) (first encoders))
                  [body* content-type]
                  (if binary?
                    (let [body* (encoder body)
                          ctype (str (enc-type :type) "/" (enc-type :sub-type))]
                      [body* ctype])
                    (let [^String char-enc (if (string? charset) charset (charset req))
                          ^String body-string (encoder body)
                          body* (.getBytes body-string char-enc)
                          ctype (str (enc-type :type) "/" (enc-type :sub-type)
                                     "; charset=" char-enc)]
                      [body* ctype]))
                  body-length (count body*)]
              (-> response
                  (assoc :body (io/input-stream body*))
                  (res/content-type content-type)
                  (res/header "Content-Length" body-length)))
            response)
          (catch Exception e
            (handle-error e req response)))))))
