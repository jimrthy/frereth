(ns user
  (:require [byte-streams :as b-s]
            [cider.piggieback]
            [cljs.repl.browser :as cljs-browser]
            [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.repl :refer (apropos dir doc pst root-cause source)]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as test]
            [clojure.test.check :refer (quick-check)]
            [clojure.test.check.clojure-test :refer (defspec)]
            [clojure.test.check.generators :as lo-gen]
            [clojure.test.check.properties :as props]
            [clojure.test.check.generators :as lo-gen]
            ;; These are moderately useless under boot.
            [clojure.tools.namespace.repl :refer (refresh refresh-all)]
            [frereth-cp.message :as msg]
            [frereth-cp.shared.bit-twiddling :as b-t]
            [frereth-cp.shared.logging :as log]
            [frereth-cp.shared.specs :as shared-specs]
            [frereth-cp.util :as utils]
            [integrant.repl :refer [clear go halt prep init reset reset-all] :as ig-repl]
            [integrant.repl.state :as ig-state]
            [manifold.deferred :as dfrd]
            [manifold.stream :as strm]))

(defn cljs-repl
  ;; The last time I actually tried this, it opened a new browser window with
  ;; instructions about writing and index.html.
  ;; That was when my cljs config was pretty broken. So this might work
  ;; fine.
  ;; In practice, under cider and emacs, running cider-connect-sibling-cljs
  ;; and choosing the weaser REPL type works fine.
  ;; This seems worth keeping around as a starting point for the sake of
  ;; anyone who isn't using emacs.
  "In theory, this launches a REPL that interacts with a new browser window"
  []
  (cider.piggieback/cljs-repl (cljs-browser/repl-env)))
