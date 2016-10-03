(defproject egg-timer "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.7.1"

  :dependencies [[cljsjs/react "15.2.1-1"]
                 [cljsjs/react-dom "15.2.1-1"]
                 ;; This seems like it should really just be a dev-time dependency.
                 ;; Q: How do I make that work?
                 ;; I really just want its functionality called from cards.html,
                 ;; which seems like it should mean a separate compiled output.
                 ;; Which is definitely being created.
                 [devcards "0.2.2"]
                 [org.clojure/clojure "1.9.0-alpha12"]
                 [org.clojure/clojurescript "1.9.229"]
                 [org.clojure/core.async "0.2.391"
                  :exclusions [org.clojure/tools.reader]]
                 [sablono "0.7.4"]]

  :plugins [[lein-figwheel "0.5.8"]
            [lein-cljsbuild "1.1.4" :exclusions [[org.clojure/clojure]]]]

  :source-paths ["src/client" "src/server"]

  :clean-targets ^{:protect false} ["resources/public/js/compiled" "target"]

  :cljsbuild {:builds
              [{:id "devcards"
                :source-paths ["src/renderer"]
                :figwheel {:devcards true  ;; <- note this
                           :websocket-host "10.0.3.152"
                           ;; :open-urls will pop open your application
                           ;; in the default browser once Figwheel has
                           ;; started and complied your application.
                           ;; Comment this out once it no longer serves you.
                           ;; :open-urls ["http://localhost:3449/cards.html"]
                           }
                :compiler {:main       "egg-timer.core"
                           :asset-path "js/compiled/devcards_out"
                           :output-to  "resources/public/js/compiled/egg_timer_devcards.js"
                           :output-dir "resources/public/js/compiled/devcards_out"
                           :source-map-timestamp true }}
               {:id "worker"
                :source-paths ["src/worker"]
                :compiler {:main egg-worker.core
                           :asset-path "js/compiled/out"
                           :optimizations :simple
                           :output-to "resources/public/js/compiled/egg_worker.js"
                           :source-map-timestamp true}}
               {:id "dev"
                :source-paths ["src/renderer"]

                ;; the presence of a :figwheel configuration here
                ;; will cause figwheel to inject the figwheel client
                ;; into your build
                :figwheel {:on-jsload "egg-timer.core/on-js-reload"
                           ;; :open-urls will pop open your application
                           ;; in the default browser once Figwheel has
                           ;; started and complied your application.
                           ;; Comment this out once it no longer serves you.
                           ;;:open-urls ["http://localhost:3449/index.html"]
                           :websocket-host #_:js-client-host "10.0.3.152"
                           }

                :compiler {:main egg-timer.core
                           :asset-path "js/compiled/out"
                           :output-to "resources/public/js/compiled/egg_timer.js"
                           :output-dir "resources/public/js/compiled/out"
                           :source-map-timestamp true
                           ;; To console.log CLJS data-structures make sure you enable devtools in Chrome
                           ;; https://github.com/binaryage/cljs-devtools
                           :preloads [devtools.preload]}}
               ;; This next build is an compressed minified build for
               ;; production. You can build this with:
               ;; lein cljsbuild once min
               {:id "min"
                :source-paths ["src/renderer"]
                :compiler {:output-to "resources/public/js/compiled/egg_timer.js"
                           :main egg-timer.core
                           :optimizations :advanced
                           :pretty-print false}}
               {:id "min-worker"
                :source-paths ["src/worker"]
                :compiler {:output-to "resources/public/js/compiled/egg_worker.js"
                           :main egg-worker.core
                           :optimizations :advanced
                           :pretty-print false}}]}

  :figwheel {;; :http-server-root "public" ;; default and assumes "resources"
             ;; :server-port 3449 ;; default
             ;; TODO: Get local IP address instead of hard-coding
             :server-ip "10.0.3.152" #_ "localhost"

             :css-dirs ["resources/public/css"] ;; watch and update CSS

             ;; Start an nREPL server into the running figwheel process
             ;; :nrepl-port 7888

             ;; Server Ring Handler (optional)
             ;; if you want to embed a ring handler into the figwheel http-kit
             ;; server, this is for simple ring servers, if this

             ;; doesn't work for you just run your own server :) (see lein-ring)

             ;; :ring-handler hello_world.server/handler

             ;; To be able to open files in your editor from the heads up display
             ;; you will need to put a script on your path.
             ;; that script will have to take a file path and a line number
             ;; ie. in  ~/bin/myfile-opener
             ;; #! /bin/sh
             ;; emacsclient -n +$2 $1
             ;;
             ;; :open-file-command "myfile-opener"

             ;; if you are using emacsclient you can just use
             :open-file-command "emacsclient"

             ;; if you want to disable the REPL
             ;; :repl false

             ;; to configure a different figwheel logfile path
             ;; :server-logfile "tmp/logs/figwheel-logfile.log"
             }


  ;; setting up nREPL for Figwheel and ClojureScript dev
  ;; Please see:
  ;; https://github.com/bhauman/lein-figwheel/wiki/Using-the-Figwheel-REPL-within-NRepl


  :profiles {:dev {:dependencies [[binaryage/devtools "0.8.2"]
                                  [com.cemerick/piggieback "0.2.1"]
                                  [figwheel-sidecar "0.5.8"]]
                   ;; need to add dev source path here to get user.clj loaded
                   :source-paths ["src/renderer" "dev"]
                   ;; for CIDER
                   ;; :plugins [[cider/cider-nrepl "0.12.0"]]
                   :repl-options {;; for nREPL dev you really need to limit output
                                  ;; :init (set! *print-length* 50)
                                  :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}}

)
