(ns frewreb.system
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [frewreb.client :as client]
            [frewreb.router :as route]
            [frewreb.server :as httpd]))

(defn system
  "Returns a new instance of the whole application"
  []
  {:client (client/init)
   :router (route/init)
   :socket-killer nil
   :web-killer nil})

(defn start
  "Peform the side-effects to make the system usable"
  [system]
  (let [started-router (route/start (:router system))
        socket-router (route/build-socket-handler started-router)
        client-killer (client/start (:client system) started-router)]
    ;; TODO:
    ;; Messages that come from the server should dispatch to @->renderer-atom.
    ;; Something needs to be listening on c to dispatch user input
    ;; messages (and any other events the renderer thinks is relevant)
    ;; to the appropriate server.
    (into system {:client-killer client-killer
                  :router started-router
                  :socket-killer (httpd/start-web 8091 socket-router)
                  :web-killer (httpd/start-web 8090 (route/all-routes))})))

(defn stop
  "Perform the side-effects to shut a system down.
The parameter name was specifically chosen to not conflict with the
system init function that gets called (and returned) at the end."
  [sys]
  ;; Note that the order things are torn down in most likely matters
  (let [doomed [:web-killer :socket-killer :client-ciller]
        kill (fn [which]
               (when-let [killer (which sys)]
                 (killer)))]
    (dorun (map kill doomed)))
  (into system {:router (route/stop (:router system))}))
