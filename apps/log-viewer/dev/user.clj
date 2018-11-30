(ns user
  ;; byte-streams.graph is failing with a syntax error at line 108
  ;; because there's no such var, s/->source.
  ;; Where s is the manifold.stream ns.
  ;; I can require that with no problem.
  ;; Trying to load byte-streams fails because of the failure
  ;; in byte-streams.graph.
  ;; This is...odd.
  (:require [byte-streams :as b-s]  ; broken!
            [cider.piggieback]
            [cljs.repl.browser :as cljs-browser]
            [clojure
             [data :as data]
             [edn :as edn]
             [repl :refer (apropos dir doc pst root-cause source)]]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as test]
            [clojure.test.check :refer (quick-check)]
            [clojure.test.check
             [clojure-test :refer (defspec)]
             [generators :as lo-gen]
             [properties :as props]
             [generators :as lo-gen]]
            ;; These are moderately useless under boot.
            [clojure.tools.namespace.repl :refer (refresh refresh-all)]
            ;; Both this and bit-twiddling rely on byte-streams
            #_[frereth-cp.message :as msg]
            [frereth-cp.shared
             #_[bit-twiddling :as b-t]
             [specs :as shared-specs]]
            [frereth-cp.util :as utils]
            [frereth.weald.logging :as log]
            [integrant.repl :refer [clear go halt prep init reset reset-all] :as ig-repl]
            [integrant.repl.state :as ig-state]
            [manifold
             [deferred :as dfrd]
             [stream :as strm]]))

(defn cljs-repl
  ;; The last time I actually tried this, it opened a new browser window with
  ;; instructions about writing and index.html.

  ;; In practice, under cider and emacs, running cider-connect-sibling-cljs
  ;; and choosing the weaser REPL type, then reloading the web page works fine.

  ;; This seems worth keeping around as a starting point for the sake of
  ;; anyone who isn't using emacs.
  "In theory, this launches a REPL that interacts with a new browser window"
  []
  (cider.piggieback/cljs-repl (cljs-browser/repl-env)
                              :host "0.0.0.0"
                              :launch-browser false
                              :port 9001))
