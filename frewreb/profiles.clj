;;; ****************************** NOTES ******************************
;;; Defines four profiles:
;;;
;;; - :shared
;;; - :dev
;;; - :simple
;;; - :advanced
;;;
;;; the :dev, :simple and :advanced profiles are composite profiles,
;;; meaning that they share the content of :shared profile.
;;; *******************************************************************

{:shared {:clean-targets ["out" :target-path]
          :test-paths ["test/clj" "test/cljs"]
          :resources-paths ["resources"]
          :plugins [[com.cemerick/clojurescript.test "0.2.1"]]
          :cljsbuild
          {:builds {:frewreb
                    {:source-paths ["test/cljs"]
                     :compiler
                     {:output-dir "resources/public/js"
                      :source-map "resources/public/js/frewreb.js.map"}}}
           :test-commands {"phantomjs"
                           ["phantomjs" :runner "resources/public/js/frewreb.js"]}}}
 :dev [:shared
       {:resources-paths ["resources"]
        :source-paths ["resources/dev/repl" "dev"]
        :dependencies [[org.clojure/tools.namespace "0.2.4"]
                       [org.clojure/java.classpath "0.2.2"]]
        :plugins [[com.cemerick/austin "0.1.4" ]]
        :cljsbuild
        {:builds {:frewreb
                 {:source-paths ["resources/dev/repl"]
                  :compiler
                  {:optimizations :whitespace
                   :pretty-print true}}}}

        :injections [(require 'cemerick.austin.repls)
                     (defn browser-repl []
                       (cemerick.austin.repls/cljs-repl (reset! cemerick.austin.repls/browser-repl-env
                                                                (cemerick.austin/repl-env))))]}]
 ;; simple profile.
 :simple [:shared
          {:cljsbuild
           {:builds {:frewreb
                     {:compiler {:optimizations :simple
                                 :pretty-print false}}}}}]
 ;; advanced profile
 :advanced [:shared
            {:cljsbuild
             {:builds {:frewreb
                       {:source-paths ["test/cljs"]
                        :compiler
                        {:optimizations :advanced
                         :pretty-print false}}}}}]}

