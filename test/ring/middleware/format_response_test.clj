(ns ring.middleware.format-response-test
  (:require [clojure.test :refer :all]
            [ring.middleware.formatters :refer :all]
            [ring.middleware.format-response :refer :all]
            [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [cognitect.transit :as transit])
  (:import [java.io ByteArrayInputStream]))

(deftest can-encode?-accept-any-type
  (is (can-encode? {:enc-type {:type "foo" :sub-type "bar"}}
                   {:type "*" :sub-type "*"})))

(deftest can-encode?-accept-any-sub-type
  (let [encoder {:enc-type {:type "foo" :sub-type "bar"}}]
    (is (can-encode? encoder
                     {:type "foo" :sub-type "*"}))
    (is (not (can-encode? encoder
                          {:type "foo" :sub-type "buzz"})))))

(deftest can-encode?-accept-specific-type
  (let [encoder {:enc-type {:type "foo" :sub-type "bar"}}]
    (is (can-encode? encoder
                     {:type "foo" :sub-type "bar"}))
    (is (not (can-encode? encoder
                          {:type "foo" :sub-type "buzz"})))))

(deftest gives-preferred-encoder
  (let [accept [{:type "text"
                 :sub-type "*"}
                {:type "application"
                 :sub-type "json"
                 :q 0.5}]
        req {:headers {"accept" accept}}
        html-encoder {:enc-type {:type "text" :sub-type "html"}}
        json-encoder {:enc-type {:type "application" :sub-type "json"}}]
    (is (= html-encoder (preferred-encoder [json-encoder html-encoder] req)))
    (is (= json-encoder (preferred-encoder [json-encoder html-encoder] {})))
    (is (nil? (preferred-encoder [{:enc-type {:type "application"
                                              :sub-type "edn"}}]
                                 req)))))

(defn stream [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(def json-echo
  (wrap-format-response identity {:formats [:json-kw]}))

(defn file-type [ct]
  (if-let [[_ x] (re-find #"^([^;]*)" ct)]
    x))

(defn charset [ct]
  (if-let [[_ x] (re-find #"charset=(.*)$" ct)]
    x))

(deftest test-utils
  (is (= "application/json" (file-type "application/json; charset=utf-8")))
  (is (= "application/json" (file-type "application/json")))
  (is (= "utf-8" (charset "application/json; charset=utf-8"))))

(deftest noop-with-nil
  (let [body nil
        req {:body body}
        resp (json-echo req)]
    (is (= nil (:body resp)))))

(deftest noop-with-missing-body
  (let [req {:status 200}
        resp (json-echo req)]
    (is (= nil (:body resp)))))

(deftest noop-with-string
  (let [body "<xml></xml>"
        req {:body body}
        resp (json-echo req)]
    (is (= body (:body resp)))))

(deftest noop-with-stream
  (let [body "<xml></xml>"
        req {:body (stream body)}
        resp (json-echo req)]
    (is (= body (slurp (:body resp))))))

(deftest format-json-hashmap
  (let [body {:foo "bar"}
        req {:body body}
        resp (json-echo req)]
    (is (= (json/generate-string body) (slurp (:body resp))))
    (is (= "application/json" (file-type (get-in resp [:headers "Content-Type"]))))
    (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

(deftest format-json-prettily
  (let [body {:foo "bar"}
        req {:body body}
        resp ((wrap-format-response identity {:formats [:json-kw], :json-kw {:pretty true}}) req)]
    (is (.contains (slurp (:body resp)) "\n "))))

(deftest returns-correct-charset
  (let [body {:foo "bârçï"}
        req {:body body :headers {"accept-charset" "utf8; q=0.8 , utf-16"}}
        resp ((wrap-format-response identity {:formats [:json-kw]}) req)]
    (is (= "utf-16" (charset (get-in resp [:headers "Content-Type"]))))
    (is (= 32 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

(deftest returns-utf8-by-default
  (let [body {:foo "bârçï"}
        req {:body body :headers {"accept-charset" "foo"}}
        resp ((wrap-format-response identity {:formats [:json-kw]}) req)]
    (is (= "utf-8" (charset (get-in resp [:headers "Content-Type"]))))
    (is (= 18 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

(def clojure-echo
  (wrap-format-response identity {:formats [:edn]}))

(deftest format-clojure-hashmap
  (let [body {:foo "bar"}
        req {:body body}
        resp (clojure-echo req)]
    (is (= body (read-string (slurp (:body resp)))))
    (is (= "application/edn" (file-type (get-in resp [:headers "Content-Type"]))))
    (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

(def yaml-echo
  (wrap-format-response identity {:formats [:yaml]}))

(deftest format-yaml-hashmap
  (let [body {:foo "bar"}
        req {:body body}
        resp (yaml-echo req)]
    (is (= (yaml/generate-string body) (slurp (:body resp))))
    (is (= "application/x-yaml" (file-type (get-in resp [:headers "Content-Type"]))))
    (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

;;;;;;;;;;;;;
;; Transit ;;
;;;;;;;;;;;;;

(defn read-transit
  [fmt in]
  (let [rdr (transit/reader in fmt)]
    (transit/read rdr)))

(def transit-json-echo
  (wrap-format-response identity {:formats [:transit-json]}))

(deftest format-transit-json-hashmap
  (let [body {:foo "bar"}
        req {:body body}
        resp (transit-json-echo req)]
    (is (= body (read-transit :json (:body resp))))
    (is (= "application/transit+json" (get-in resp [:headers "Content-Type"])))
    (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

(def transit-msgpack-echo
  (wrap-format-response identity {:formats [:transit-msgpack]}))

(deftest format-transit-msgpack-hashmap
  (let [body {:foo "bar"}
        req {:body body}
        resp (transit-msgpack-echo req)]
    (is (= body (read-transit :msgpack (:body resp))))
    (is (= "application/transit+msgpack" (get-in resp [:headers "Content-Type"])))
    (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

(defn echo-with-default-body [req] (assoc req :body (get req :body {})))

(def restful-echo
  (wrap-format-response echo-with-default-body))

(defrecord SafeEncoder [name content-type]
  FormatEncoder
  (create-encoder [_ opts]
    (fn [_ _]
      (throw (RuntimeException. "Memento mori")))))

(def safe-restful-echo
  (wrap-format-response echo-with-default-body
                        {:handle-error (fn [_ _ _] {:status 500})
                         :formats [(SafeEncoder. :safe "foo/bar")]}))

(deftest format-hashmap-to-preferred
  (let [ok-accept "application/edn, application/json;q=0.5"
        ok-req {:headers {"accept" ok-accept}}]
    (is (= "application/edn; charset=utf-8"
           (get-in (restful-echo ok-req) [:headers "Content-Type"])))
    (is (= "application/json"
           (file-type (get-in (restful-echo {:headers {"accept" "foo/bar"}})
                              [:headers "Content-Type"]))))
    (is (= 500 (get (safe-restful-echo {:headers {"accept" "foo/bar"}}) :status)))))

(deftest format-restful-hashmap
  (let [body {:foo "bar"}]
    (doseq [accept ["application/edn"
                    "application/json"
                    "application/x-yaml"
                    "application/transit+json"
                    "application/transit+msgpack"
                    "text/html"]]
      (let [req {:body body :headers {"accept" accept}}
            resp (restful-echo req)]
        (is (= accept (file-type (get-in resp [:headers "Content-Type"]))))
        (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

    (let [req {:body body}
          resp (restful-echo req)]
      (is (= "application/json" (file-type (get-in resp [:headers "Content-Type"]))))
      (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"])))))))

(defrecord CustomEncoder [name content-type]
  FormatEncoder
  (create-encoder [_ _]
    (fn [_ _]
      [(.getBytes "foobar") content-type])))

(def custom-restful-echo
  (wrap-format-response identity {:formats [(CustomEncoder. :custom "text/foo")]}))

(deftest format-custom-restful-hashmap
  (let [req {:body {:foo "bar"} :headers {"accept" "text/foo"}}
        resp (custom-restful-echo req)]
    (is (= "text/foo" (file-type (get-in resp [:headers "Content-Type"]))))
    (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

(def restful-echo-pred
  (wrap-format-response identity {:predicate (fn [_ resp]
                                               (::serializable? resp))}))

(deftest custom-predicate
  (let [req {:body {:foo "bar"}}
        resp-non-serialized (restful-echo-pred (assoc req ::serializable? false))
        resp-serialized     (restful-echo-pred (assoc req ::serializable? true))]
    (is (map? (:body resp-non-serialized)))
    (is (instance? java.io.BufferedInputStream (:body resp-serialized) nil))))

;;
;; Transit options
;;

(defrecord Point [x y])

(def writers
  {Point (transit/write-handler (constantly "Point") (fn [p] [(:x p) (:y p)]))})

(def custom-transit-echo
  (wrap-format-response identity {:formats [:transit-json], :transit-json {:handlers writers}}))

(deftest write-custom-transit
  (let [req {:body (Point. 1 2)}
        resp (custom-transit-echo req)]
    (is (= "[\"~#Point\",[1,2]]" (slurp (:body resp))))))
