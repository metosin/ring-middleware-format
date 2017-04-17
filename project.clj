(defproject metosin/ring-middleware-format "0.6.0"
  :description "Ring middleware for parsing parameters and emitting
  responses in various formats (mainly JSON, YAML and Transit out of
  the box)"
  :url "https://github.com/metosin/ring-middleware-format"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :dependencies [[org.clojure/core.memoize "0.5.7"]
                 [ring "1.4.0"]
                 [cheshire "5.5.0"]
                 [org.clojure/tools.reader "0.9.2"]
                 [com.ibm.icu/icu4j "55.1"]
                 [clj-yaml "0.4.0"]
                 [clojure-msgpack "1.1.1"]
                 [com.cognitect/transit-clj "0.8.281"]]
  :plugins [[codox "0.8.13"]]
  :codox {:src-dir-uri "http://github.com/metosin/ring-middleware-format/blob/master/"
          :src-linenum-anchor-prefix "L"
          :defaults {:doc/format :markdown}}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.7.0"]]}
             :1.6 {:dependencies [[org.clojure/clojure "1.6.0"]]}}
  :aliases {"all" ["with-profile" "dev:dev,1.6"]
            "test-ancient" ["test"]})
