(ns frewreb.web
  (:require [compojure.handler :as handler]
            [frewreb.router :as r]
            [frewreb.system :as s]))

(defn make-handler
  "Main app entry point that gets called during reset (aka reload-frodo!)
This fails in that there really isn't any way to specify a stop handler."
  []
  (throw (RuntimeException. "obsolete"))
  (swap! system (s/start))
  (handler/api (all-routes)))
