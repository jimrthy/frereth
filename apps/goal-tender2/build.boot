(def project 'goal-tender2)
(def version "0.1.0-SNAPSHOT")

(set-env! :resource-paths #{"resources" "src"}
          :source-paths   #{"dev" "test"}
          :dependencies   '[[com.datomic/datomic-free "0.9.5561"]
                            [org.clojure/clojure "1.9.0-alpha17"]
                            [org.clojure/tools.namespace "0.3.0-alpha4"]
                            [adzerk/boot-test "RELEASE" :scope "test"]])

(task-options!
 aot {:namespace   #{'goal-tender2.core}}
 pom {:project     project
      :version     version
      :description "There aren't enough TODO list implementations floating around"
      :url         "http://example/FIXME"
      :scm         {:url "https://github.com/yourname/goal-tender2"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}}
 jar {:main        'goal-tender2.core
      :file        (str "goal-tender2-" version "-standalone.jar")})

(deftask build
  "Build the project locally as a JAR."
  [d dir PATH #{str} "the set of directories to write to (target)."]
  (let [dir (if (seq dir) dir #{"target"})]
    (comp (aot) (pom) (uber) (jar) (target :dir dir))))

(deftask run
  "Run the project."
  [a args ARG [str] "the arguments for the application."]
  (require '[goal-tender2.core :as app])
  (apply (resolve 'app/-main) args))

(require '[adzerk.boot-test :refer [test]])
