(ns frewreb.web
  (:require [compojure.handler :as handler]
            [frewreb.router :as r]
            [frewreb.system :as s]))

(let [system (atom (sys/system))
      socket-proxy (fn [req]
                     (r/socket-handler req @system))]
  (defn all-routes
    []
    (routes   
     (GET "/" _ (r/page))
     (GET "/ws" [] (socket-proxy))
     (resources "/")
     (not-found "Missing")))

  (defn make-handler
    "Main app entry point that gets called during reset (aka reload-frodo!)
This fails in that there really isn't any way to specify a stop handler."
    []
    (swap! system (s/start))
    (handler/api (all-routes))))
