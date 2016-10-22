(defproject goal-tender "0.1.0-SNAPSHOT"
  :description "For making sure your goals get met"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha13"]]
  :main ^:skip-aot goal-tender.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
