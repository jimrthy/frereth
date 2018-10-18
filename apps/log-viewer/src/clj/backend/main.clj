(ns backend.main
  (:require [integrant.core :as ig]
            integrant.repl)
  (:gen-class))

(defn init
  ([] (init nil))
  ([opts]
   (require 'backend.system)
   ((resolve 'backend.system/ctor) opts)))

(defn setup-app! [opts]
  (integrant.repl/set-prep! #(init opts)))

(defn -main [& args]
  (setup-app! {})
  (integrant.repl/go))
