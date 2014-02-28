(ns user
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer (pprint)]
            [clojure.repl :refer :all]
            [clojure.test :as test]
            [clojure.tools.namespace.repl :refer (refresh refresh-all)]
            [frewreb.system :as system]))

(def system nil)

(defn init
  "Constructs the current development system"
  []
  (alter-var-root #'system
                  (constantly (system/system))))

(defn start
  "Starts the current development system"
  []
  (alter-var-root #'system system/start))

(defn stop
  "Shuts down and destroys the current development system"
  []
  (alter-var-root #'system
                  (fn [s]
                    (when s (system/stop s)))))

(defn go
  "Initializes the current development system and starts it running"
  []
  (init)
  (start))

(defn reset
  "The heart of Stuart Sierra's workflow"
  []
  (stop)
  (refresh :after 'user/go))
