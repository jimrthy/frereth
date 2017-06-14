(ns user
  "Add requirements to pieces that I use constantly from the REPL

Pieces to start up a system seem reasonable

tools.namespace for refresh is pretty much mandatory"
  (:require [clojure.pprint :refer (pprint)]
            [clojure.repl :refer :all]  ; dir is very useful
            [clojure.tools.namespace.repl :refer (refresh refresh-all)]
            [datomic.api :as d]
            [goal-tender2.catch :as catch]
            [goal-tender2.core :as gt]))

;; Need to start somewhere, and I'm not digging up a system library tonight
(def cxn nil)

(comment
  (def dbg-cxn (gt/do-schema-installation "sandcastle"))
  dbg-cxnq

  (catch/add-dream (gt/build-url "sandcastle")
                   "What I want to do this summer")
  (catch/list-dreams (gt/build-url "sandcastle"))
  )

(defn start
  []
  (alter-var-root cxn
                  (fn [_]
                    (gt/do-schema-installation "sandcastle"))))

(defn stop
  []
  ;; TODO: Write this
  (throw (RuntimeException. "clean up sandcastle")))
