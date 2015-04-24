(ns ring.middleware.format-utils
  (:require [clojure.core.memoize :refer [lu]]
            [clojure.string :as string])
  (:import [java.nio.charset Charset]
           [java.io InputStream ByteArrayOutputStream]
           [com.ibm.icu.text CharsetDetector]))

(def available-charsets
  "Set of recognised charsets by the current JVM"
  (into #{} (map string/lower-case (.keySet (Charset/availableCharsets)))))

;;
;; Parse accept header
;;

(defn sort-by-check
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
              (let [[media-range & rest] (string/split (string/trim val) #";")
                    type (zipmap [:type :sub-type]
                                 (string/split (string/trim media-range) #"/"))]
                (cond (nil? rest)
                      (assoc type :q 1.0)
                      (= (first (string/triml (first rest)))
                         \q) ;no media-range params
                      (assoc type :q
                                  (Double/parseDouble
                                    (second (string/split (first rest) #"="))))
                      :else
                      (assoc (if-let [q-val (second rest)]
                               (assoc type :q
                                           (Double/parseDouble
                                             (second (string/split q-val #"="))))
                               (assoc type :q 1.0))
                        :parameter (string/trim (first rest))))))
            (string/split accept-header #","))
       (sort-by-check :parameter nil)
       (sort-by-check :type "*")
       (sort-by-check :sub-type "*")
       (sort-by :q >)))

(def parse-accept-header
  "Memoized form of [[parse-accept-header*]]"
  (lu parse-accept-header* {} :lu/threshold 500))

;;
;; Parse charset
;;

(defn preferred-charset
  "Returns an acceptable choice from a list of [*charset* *quality-score*]"
  [charsets]
  (or
    (->> (sort-by second charsets)
         (filter (comp available-charsets first))
         (first)
         (first))
    "utf-8"))

(defn parse-charset-accepted
  "Parses an *accept-charset* string to a list of [*charset* *quality-score*]"
  [v]
  (let [segments (string/split v #",")
        choices (for [segment segments
                      :when (not (empty? segment))
                      :let [[_ charset qs] (re-find #"([^;]+)(?:;\s*q\s*=\s*([0-9\.]+))?" segment)]
                      :when charset
                      :let [qscore (try
                                     (Double/parseDouble (string/trim qs))
                                     (catch Exception e 1))]]
                  [(string/trim charset) qscore])]
    choices))

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

(defn guess-charset
  [{:keys [^bytes body]}]
  (try
    (let [^CharsetDetector detector (CharsetDetector.)]
      (.enableInputFilter detector true)
      (.setText detector body)
      (let [m (.detect detector)
            encoding (.getName m)]
        (if (available-charsets encoding)
          encoding)))
    (catch Exception _ nil)))

(defn get-charset
  [{:keys [content-type] :as req}]
  (if content-type
    (second (re-find #";\s*charset=([^\s;]+)" content-type))))

(defn get-or-guess-charset
  "Tries to get the request encoding from the header or guess
  it if not given in *Content-Type*. Defaults to *utf-8*"
  [req]
  (or
    (get-charset req)
    (guess-charset req)
    "utf-8"))

(defn get-or-default-charset
  [req]
  (or
    (get-charset req)
    "utf-8"))

;;
;; Random
;;

(defn slurp-to-bytes
  ^bytes [^InputStream in]
  (if in
    (let [buf (byte-array 4096)
          out (ByteArrayOutputStream.)]
      (loop []
        (let [r (.read in buf)]
          (when (not= r -1)
            (.write out buf 0 r)
            (recur))))
      (.toByteArray out))))

(defn get-content-type ^String [req]
  (or (get req :content-type)
      (get-in req [:headers "Content-Type"])
      (get-in req [:headers "content-type"])))
