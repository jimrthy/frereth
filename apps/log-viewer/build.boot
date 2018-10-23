(def project-name "com.frereth.log-viewer")

(require '[clojure.java.shell :as sh])

(defn next-version [version]
  (when version
    (let [[a b] (next (re-matches #"(.*?)([\d]+)" version))]
      (when (and a b)
        (str a (inc (Long/parseLong b)))))))

(def default-version
  "Really just for running inside docker w/out git tags"
  "0.0.1-???-dirty")
(defn deduce-version-from-git
  "Avoid another decade of pointless, unnecessary and error-prone
  fiddling with version labels in source code.

  Important note: this only works if your repo has tags!
  And the tags this cares about need to be numeric. Can't
  use, e.g. 0.0.1-SNAPSHOT.

  Another interesting detail is that tags must have commit
  messages for describe to work properly:
  `git tag 0.0.2 -m 'Move forward'`"
  []
  (let [[version commits hash dirty?]
        (next (re-matches #"(.*?)-(.*?)-(.*?)(-dirty)?\n"
                          (:out (sh/sh "git"
                                       "describe"
                                       "--always"
                                       "--dirty"
                                       "--long"
                                       "--tags"
                                       "--match" "[0-9].*"))))]
    (if commits
      (cond
        dirty? (str (next-version version) "-" hash "-dirty")
        (pos? (Long/parseLong commits)) (str (next-version version) "-" hash)
        :otherwise version)
      default-version)))

(def project 'com.frereth/log-viewer)
(def version #_"0.1.0-SNAPSHOT" (deduce-version-from-git))

(set-env! :dependencies   '[[adzerk/boot-cljs "2.1.4" :scope "test"]
                            [adzerk/boot-cljs-repl "0.4.0" :scope "test"]
                            [adzerk/boot-reload "0.6.0" :scope "test"]
                            [bidi "2.1.4"]
                            [cider/piggieback "0.3.9" :scope "test" :exclusions [com.google.guava/guava
                                                                                 com.google.javascript/closure-compiler-externs
                                                                                 com.google.javascript/closure-compiler-unshaded
                                                                                 nrepl
                                                                                 org.clojure/clojure
                                                                                 org.clojure/clojurescript
                                                                                 org.clojure/tools.reader]]
                            [com.cemerick/pomegranate
                             "1.1.0"
                             :exclusions [com.google.guava/guava
                                          commons-codec
                                          commons-io
                                          org.clojure/clojure
                                          org.slf4j/jcl-over-slf4j
                                          org.slf4j/slf4j-api]
                             :scope "test"]
                            [com.cognitect/transit-clj "0.8.313" :exclusions [commons-codec]]
                            [com.cognitect/transit-cljs "0.8.256"]
                            [crisptrutski/boot-cljs-test "0.3.4" :scope "test"]
                            [deraen/boot-sass "0.3.1" :scope "test"]
                            [deraen/boot-less "0.6.2" :scope "test"]
                            [doo "0.1.10" :scope "test" :exclusions [args4j
                                                                     com.google.code.findbugs/jsr305
                                                                     com.google.code.gson/gson
                                                                     com.google.guava/guava
                                                                     com.google.javascript/closure-compiler-externs
                                                                     com.google.javascript/closure-compiler-unshaded
                                                                     com.google.protobuf/protobuf-java
                                                                     org.clojure/clojure
                                                                     org.clojure/clojurescript
                                                                     org.clojure/clojure.specs.alpha
                                                                     org.clojure/google-closure-library
                                                                     org.clojure/google-closure-library-third-party
                                                                     org.clojure/spec.alpha
                                                                     org.clojure/tools.reader]]
                            [metosin/boot-alt-test "0.3.2" :scope "test"]
                            [metosin/boot-deps-size "0.1.0" :scope "test"]
                            [nrepl "0.4.5" :exclusions [org.clojure/clojure]]
                            [org.clojure/clojure "1.10.0-beta3"]
                            [org.clojure/clojurescript "1.10.339" :scope "test" :exclusions [commons-codec
                                                                                             com.cognitect/transit-clj
                                                                                             com.cognitect/transit-java
                                                                                             org.clojure/clojure]]
                            [org.clojure/spec.alpha "0.2.176" :exclusions [org.clojure/clojure]]
                            [org.clojure/test.check "0.10.0-alpha3" :scope "test" :exclusions [org.clojure/clojure]]
                            ;; For boot-less
                            [org.slf4j/slf4j-nop "1.7.25" :scope "test"]
                            ;; This is the task that combines all the linters
                            [tolitius/boot-check "0.1.11" :scope "test" :exclusions [boot/core
                                                                                     org.clojure/clojure
                                                                                     org.tcrawley/dynapath]]
                            [weasel "0.7.0" :scope "test" :exclusions [args4j
                                                                       com.google.code.findbugs/jsr305
                                                                       com.google.code.gson/gson
                                                                       com.google.guava/guava
                                                                       com.google.javascript/closure-compiler-externs
                                                                       com.google.protobuf/protobuf-java
                                                                       org.clojure/clojure
                                                                       org.clojure/clojurescript
                                                                       org.clojure/google-closure-library
                                                                       org.clojure/google-closure-library-third-party
                                                                       org.clojure/tools.reader]]

                            ;; Backend
                            ;; Q: Conflict w/ core.specs.alpha ?
                            ;; A: Must have an older version installed
                            [frereth-cp "0.0.1-SNAPSHOT"]
                            [integrant "0.7.0" :exclusions [org.clojure/clojure
                                                            org.clojure/core.specs.alpha
                                                            org.clojure/spec.alpha]]
                            [integrant/repl "0.3.1" :exclusions [integrant
                                                                 org.clojure/clojure
                                                                 org.clojure/core.specs.alpha
                                                                 org.clojure/spec.alpha
                                                                 org.clojure/tools.namespace]]
                            [javax.servlet/servlet-api "3.0-alpha-1"]  ; for ring multipart middleware
                            [metosin/ring-http-response "0.9.0" :exclusions [clj-time
                                                                             commons-codec
                                                                             commons-fileupload
                                                                             commons-io
                                                                             joda-time
                                                                             potemkin
                                                                             ring/ring-codec
                                                                             ring/ring-core]]
                            [org.clojure/tools.namespace "0.3.0-alpha4" :exclusions [org.clojure/clojure
                                                                                     org.clojure/tools.reader]]
                            [ring/ring-core "1.7.0" :exclusions [org.clojure/clojure]]

                            ;; Frontend
                            [reagent "0.8.1" :scope "test" :exclusions [org.clojure/clojure
                                                                        org.clojure/spec.alpha]]
                            [binaryage/devtools "0.9.10" :scope "test" :exclusions [org.clojure/tools.reader]]
                            ;; Q: Why?
                            [cljsjs/babel-standalone "6.18.1-3" :scope "test" :exclusions [org.clojure/clojure]]

                            ;; These next 2 have updated to 4.1.3.
                            ;; TODO: See how well that works
                            ;; LESS
                            [org.webjars/bootstrap "3.3.7-1"]
                            ;; SASS
                            [org.webjars.bower/bootstrap "4.1.1" :exclusions [org.webjars.bower/jquery]]]
          :project project
          :resource-paths #{"src/clj" "src/cljc"}
          ;; Test path can be included here as source-files are not included in JAR
          ;; Just be careful to not AOT them
          :source-paths   #{"dev" "src/cljs" "src/less" "src/scss" "test/clj" "test/cljs"})

(require
 '[adzerk.boot-cljs :refer [cljs]]
 '[adzerk.boot-cljs-repl :refer [cljs-repl start-repl repl-env]]
 '[adzerk.boot-reload :refer [reload]]
 ;; This one's defined under src/clj/
 '[backend.boot :refer [start-app]]
 '[boot.pod :as pod]
 '[deraen.boot-less :refer [less]]
 '[deraen.boot-sass :refer [sass]]
 '[metosin.boot-alt-test :refer [alt-test]]
 '[metosin.boot-deps-size :refer [deps-size]]
 '[crisptrutski.boot-cljs-test :refer [test-cljs]]
 '[integrant.repl :refer [clear go halt prep init reset reset-all]]
 '[tolitius.boot-check :as check])

;; Really need to consider less vs. sass vs. garden
(task-options!
 aot {:namespace   #{'backend.main}}
 cljs-repl {:nrepl-opts {:bind "0.0.0.0" :port 43043}
            :ip "0.0.0.0"}
 jar {:file        (str "frereth-log-viewer-" version ".jar")
      :main 'backend.main}
 less {:source-map true}
 pom {:project     (symbol project-name)
      :version     (deduce-version-from-git)
      :description "Log viewer to demo frereth architecture"
      ;; TODO: Add a real website
      :url         "https://github.com/jimrthy/frereth/apps/log-viewer"
      :scm         {:url "https://github.com/jimrthy/frereth"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}}
 repl-env {:ip "0.0.0.0"}
 sass {:source-map true}
 ;; Either Weasel or CIDER ignores this setting. Need to run
 ;; (start-repl) manually and then open a browser connection
 ;; to http://localhost:10555/index
 start-repl {:ip "0.0.0.0" :port 9001})

(deftask check-conflicts
  "Verify there are no dependency conflicts."
  []
  (with-pass-thru fs
    (require '[boot.pedantic :as pedant])
    (let [dep-conflicts (resolve 'pedant/dep-conflicts)]
      (if-let [conflicts (not-empty (dep-conflicts pod/env))]
        (throw (ex-info (str "Unresolved dependency conflicts. "
                             "Use :exclusions to resolve them!")
                        conflicts))
        (println "\nVerified there are no dependency conflicts.")))))

(deftask dev
  "Start the dev env..."
  [s speak         bool "Notify when build is done"
   p port     PORT int  "Port for web server"
   a use-sass      bool "Use Sass instead of less"
   t test-cljs     bool "Compile and run cljs tests"]
  (comp
   (watch)
   ;; boot-cljs recommends doing this with :modules to avoid code duplication.
   ;; Unless you need separate builds.
   ;; Which really is the case, since this is simulating something that
   ;; could/should have been supplied from some totally different server.
   (cljs :ids #{"js/worker"})
   ;; TODO: Switch the open-file to connect to a running emacs instance
   (reload :open-file "vim --servername log_viewer --remote-silent +norm%sG%s| %s"
           :ids #{"js/main"}
           :port 43140)
   (if use-sass
     (sass)
     (less))
   ;; This starts a repl server with piggieback middleware
   (cljs-repl :ids #{"js/main"} :ip "0.0.0.0")
   ;; Main app
   (cljs :ids #{"js/main"})
   ;; Remove cljs output from classpath but keep within fileset with output role
   (sift :to-asset #{#"^js/.*"})
   ;; Write the resources to filesystem for dev server
   (target :dir #{"dev-output"})
   ;; TODO: Experiment with this when I get web workers to compile correctly
   #_(repl :server true)
   (start-app :port port)
   (if speak
     (boot.task.built-in/speak)
     identity)))

(deftask cider-repl
  "Set up a REPL for connecting from CIDER"
  []
  ;; This belongs in here even less than it does under
  ;; the CurveCP translation. At least that one has a java
  ;; compilation step to make this a little easier to remember
  (comp (cider) (repl)))

(deftask run
  "Run the project."
  [f file FILENAME #{str} "the arguments for the application."]
  ;; This is a leftover template from another project that I
  ;; really just copy/pasted over.
  ;; Q: Does it make any sense to keep it around?
  (require '[frereth-cp.server :as app])
  (apply (resolve 'app/-main) file))

(ns-unmap *ns* 'test)
(deftask test []
  (comp
   (alt-test)
   ;; FIXME: This is not a good place to define which namespaces to test
   (test-cljs :namespaces #{"frontend.core-test"})))

(deftask autotest []
  (comp
   (watch)
   (test)))

(deftask package
  "Build the package"
  [a use-sass      bool "Use Sass instead of less"]
  (comp
   ;; Note that this doesn't offer an option between less and sass
   (if use-sass
     (sass :compression true)
     (less :compression true))
   (cljs :optimizations :advanced
         :compiler-options {:preloads nil})
   (aot)
   (pom)
   (uber)
   (jar)
   (sift :include #{#".*\.jar"})
   (target)))

(deftask local-install
  "Create a jar to go into your local maven repository"
  [d dir PATH #{str} "the set of directories to write to (target)."]
  (let [dir (if (seq dir) dir #{"target"})]
    (comp (package)  (install))))