(ns user
  "Add requirements to pieces that I use constantly from the REPL

Pieces to start up a system seem reasonable

tools.namespace for refresh is pretty much mandatory"
  (:require [clojure.pprint :refer (pprint)]
            [clojure.repl :refer :all]  ; dir is very useful
            [clojure.tools.namespace.repl :refer (refresh refresh-all)]
            [datomic.api :as d]
            [goal-tender2.core :as gt]))

;; Need to start somewhere, and I'm not digging up a system library tonight
(def cxn nil)

(comment
  (def dbg-cxn (gt/do-schema-installation "sandcastle"))
  dbg-cxnq)
(defn start
  []
  (alter-var-root cxn
                  (fn [_]
                    (gt/do-schema-installation "sandcastle"))))

(defn stop
  []
  (throw (RuntimeException. "clean up sandcastle")))