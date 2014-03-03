(ns frewreb.system
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [frewreb.server :as httpd]
            [frewreb.router :as route]))

(defn system
  "Returns a new instance of the whole application"
  []
  {:web-killer nil
   :socket-killer nil
   :router (route/init)})

(defn start
  "Peform the side-effects to make the system usable"
  [system]
  (let [started-router (route/start (:router system))
        c (async/chan)
        socket-router (route/build-socket-handler started-router)]
    ;; TODO:
    ;; Messages that come from the server should dispatch to @->renderer-atom.
    ;; Something needs to be listening on c to dispatch user input
    ;; messages (and any other events the renderer thinks is relevant)
    ;; to the appropriate server.
    (into system {:web-killer (httpd/start-web 8090 (route/all-routes))
                  :socket-killer (httpd/start-web 8091 socket-router)
                  :router started-router})))

(defn stop
  "Perform the side-effects to shut a system down.
The parameter name was specifically chosen to not conflict with the
system init function that gets called (and returned) at the end."
  [sys]
  (let [doomed [:web-killer :socket-killer]
        kill (fn [which]
               (when-let [killer (which sys)]
                 (killer)))]
    (dorun (map kill doomed)))
  (into system {:router (route/stop (:router system))}))
