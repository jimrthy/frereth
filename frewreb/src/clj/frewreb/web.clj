(ns frewreb.web
  (:require [compojure.handler :as handler]
            [frewreb.router :as r]
            [frewreb.system :as s]))

(defn make-handler
  "Main app entry point that gets called during reset (aka reload-frodo!)"
  []
  (handler/api (r/all-routes)))
