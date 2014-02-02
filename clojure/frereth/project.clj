(defproject frereth "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]]
  :main frereth.core
  :plugins [[lein-git-deps "0.0.1-SNAPSHOT"]]
  :git-dependencies [["git@github.com:jimrthy/frereth-server.git"
                      "git@github.com:jimrthy/frereth-client.git"
                      ;; TODO: How do I specify a branch other than master?
                      "git@github.com:jimrthy/frereth-renderer.git&clojure"
                      "git@github.com:jimrthy/cljeromq.git"]]
  :source-paths [".lein-git-deps/frereth-server/src"
                 ".lein-git-deps/frereth-client/src"
                 ".lein-git-deps/frereth-renderer/frereth-renderer/src"]
  :profiles {:uberjar {:aot :all}})
