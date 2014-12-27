(defproject frereth "0.1.0-SNAPSHOT"
  :description "Wrapper around individual frereth components"
  :url "http://frereth.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[com.cemerick/pomegranate "0.3.0"]
                 [com.stuartsierra/component "0.2.2"]
                 [com.taoensso/timbre "3.3.1"]
                 [frereth-renderer "0.0.1-SNAPSHOT"]
                 [frereth-server "0.1.0-SNAPSHOT"]
                 [im.chit/ribol "0.4.0"]
                 [org.clojure/clojure "1.7.0-alpha1"]
                 [prismatic/schema "0.3.3"]]
  :main frereth.core
  :profiles {:uberjar {:aot :all}
             :dev {:source-paths ["dev"]
                   :dependencies  [[clj-ns-browser "1.3.1"]
                                   [midje "1.6.3"]
                                   [org.clojure/tools.namespace "0.2.8"]
                                   [org.clojure/java.classpath "0.2.2"]
                                   ;; Umm...do I really not want this for
                                   ;; real??
                                   [org.clojure/tools.logging "0.3.1"]]}}
  :repl-options {:init-ns user})
