(defproject frewreb "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://frereth.com/FIXME"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}

  ;; We need to add src/cljs too, because cljsbuild does not add its
  ;; source-paths to the project source-paths
  :source-paths ["src/clj" "src/cljs"]

  :cljsbuild
  {:builds {;; This build is only used for including any cljs source
            ;; in the packaged jar when you issue lein jar command and
            ;; any other command that depends on it
            :frewreb
            {:source-paths ["src/cljs"]
             ;; The :jar true option is not needed to include the CLJS
             ;; sources in the packaged jar. This is because we added
             ;; the CLJS source codebase to the Leiningen
             ;; :source-paths
             ;:jar true
             ;; Compilation Options
             :compiler
             {:output-to "resources/public/js/frewreb.js"
              :optimizations :advanced
              :pretty-print false}}}}

  :dependencies [[cljs-webgl "0.1.4-SNAPSHOT"]
                 [compojure "1.1.6"]
                 [enfocus "2.0.2"]
                 [enlive "1.1.4"]
                 [http-kit "2.1.16"]
                 [org.clojars.jimrthy/cljeromq "0.1.0-SNAPSHOT" :exclusions [org.clojure/tools.macro]]
                 [org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-2173"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [ring "1.2.1"]]

  :hooks [leiningen.cljsbuild]

  :main frewreb.core

  :min-lein-version "2.3.4"

  ;; I don't think this is the problem, but just to double-check.
  ;; Austin quit working after I turned this on.
  ;;:pedantic? :warn

  :plugins [[lein-cljsbuild "1.0.1"]]

  :repl-options {:init-ns user}

  ;; We need to add src/cljs too, because cljsbuild does not add its
  ;; source-paths to the project source-paths
  :source-paths ["src/clj" "src/cljs"])
