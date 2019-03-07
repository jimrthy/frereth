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
  ;; FIXME: Use a library like environ that pulls
  ;; the configuration from the environment
  (setup-app! {})
  (integrant.repl/go))
