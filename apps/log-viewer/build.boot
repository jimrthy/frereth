(require '[clojure.java.shell :as sh])

(defn next-version [version]
  (when version
    (let [[a b] (next (re-matches #"(.*?)([\d]+)" version))]
      (when (and a b)
        (str a (inc (Long/parseLong b)))))))

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
    (cond
      dirty? (str (next-version version) "-" hash "-dirty")
      (pos? (Long/parseLong commits)) (str (next-version version) "-" hash)
      :otherwise version)))

(def project 'com.frereth/log-viewer)
(def version #_"0.1.0-SNAPSHOT" (deduce-version-from-git))

(set-env! :dependencies   '[[adzerk/boot-cljs "2.1.4" :scope "test"]
                            [adzerk/boot-cljs-repl "0.4.0" :scope "test"]
                            [adzerk/boot-reload "0.6.0-SNAPSHOT" :scope "test"]
                            [cider/piggieback "0.3.9" :scope "test"]
                            [com.cemerick/pomegranate
                             "1.0.0"
                             :exclusions [commons-codec
                                          org.clojure/clojure
                                          org.slf4j/jcl-over-slf4j]
                             :scope "test"]
                            [crisptrutski/boot-cljs-test "0.3.4" :scope "test"]
                            [doo "0.1.8" :scope "test"]
                            [metosin/boot-alt-test "0.3.2" :scope "test"]
                            [metosin/boot-deps-size "0.1.0" :scope "test"]
                            [nrepl "0.4.5"]
                            [org.clojure/clojure "1.10.0-alpha8"]
                            [org.clojure/clojurescript "1.10.339" :scope "test"]
                            [org.clojure/spec.alpha "0.2.176" :exclusions [org.clojure/clojure]]
                            [org.clojure/test.check "0.10.0-alpha3" :scope "test" :exclusions [org.clojure/clojure]]
                            ;; This is the task that combines all the linters
                            [tolitius/boot-check "0.1.11" :scope "test" :exclusions [org.tcrawley/dynapath]]
                            [weasel "0.7.0" :scope "test"]

                            ;; Backend
                            [aleph "0.4.5-alpha6"]
                            [frereth-cp "0.0.1-SNAPSHOT"]
                            [integrant "0.7.0"]
                            [integrant/repl "0.3.1"]
                            [javax.servlet/servlet-api "2.5"]  ; for ring multipart middleware
                            [metosin/ring-http-response "0.9.0"]
                            [org.clojure/tools.namespace "0.3.0-alpha4"]
                            [ring/ring-core "1.6.3"]

                            ;; Frontend
                            [reagent "0.8.1" :scope "test"]
                            [binaryage/devtools "0.9.10" :scope "test"]
                            ;; Q: Why?
                            [cljsjs/babel-standalone "6.18.1-3" :scope "test"]]
          :project project
          :resource-paths #{"src/clj" "src/cljc"}
          ;; Test path can be included here as source-files are not included in JAR
          ;; Just be careful to not AOT them
          :source-paths   #{"dev" "dev-resources" "test/clj" "test/cljs"})

(task-options!
 aot {:namespace   #{'com.frereth.client.system}}
 pom {:project     project
      :version     version
      :description "Shared frereth components"
      ;; TODO: Add a real website
      :url         "https://github.com/jimrthy/frereth/apps/log-viewer"
      :scm         {:url "https://github.com/jimrthy/frereth"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}}
 jar {:file        (str "frereth-log-viewer-" version ".jar")})

(require
 '[adzerk.boot-cljs :refer [cljs]]
 '[])
(require '[samestep.boot-refresh :refer [refresh]])
(require '[tolitius.boot-check :as check])

(deftask build
  "Build the project locally as a JAR."
  [d dir PATH #{str} "the set of directories to write to (target)."]
  ;; Note that this approach passes the raw command-line parameters
  ;; to -main, as opposed to what happens with `boot run`
  ;; TODO: Eliminate this discrepancy
  (let [dir (if (seq dir) dir #{"target"})]
    (comp (javac) (aot) (pom) (uber) (jar) (target :dir dir))))

(deftask local-install
  "Create a jar to go into your local maven repository"
  [d dir PATH #{str} "the set of directories to write to (target)."]
  (let [dir (if (seq dir) dir #{"target"})]
    (comp (pom) (jar) (target :dir dir) (install))))

(deftask cider-repl
  "Set up a REPL for connecting from CIDER"
  []
  ;; Just because I'm prone to forget one of the vital helper steps
  (comp (cider) (javac) (repl)))

(deftask run
  "Run the project."
  [f file FILENAME #{str} "the arguments for the application."]
  ;; This is a leftover template from another project that I
  ;; really just copy/pasted over.
  ;; Q: Does it make any sense to keep it around?
  (require '[frereth-cp.server :as app])
  (apply (resolve 'app/-main) file))

(require '[adzerk.boot-test :refer [test]])
