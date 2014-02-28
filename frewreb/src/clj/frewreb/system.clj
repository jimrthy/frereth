(ns frewreb.system
  (:require [clojure.core.async :as async]
            [frewreb.server :as httpd]
            [frewreb.router :as route]))

(defn system
  "Returns a new instance of the whole application"
  []
  {:web-killer nil
   :socket-killer nil
   :internal-messaging-killer nil})

(defn start
  "Peform the side-effects to make the system usable"
  [system]
  (let [c (async/chan)
        ->renderer-atom (atom nil)
        socket-router (route/build-socket-router ->renderer-atom c)]
    (into system {:web-killer (httpd/start-web route/web-router)
                  :socket-killer (httpd/start-web socket-router)
                  :internal-messaging-killer (fn []
                                               (async/close! c))})))

(defn stop
  "Perform the side-effects to shut a system down"
  [sys]
  (let [doomed [:web-killer :socket-killer :internal-messaging-killer]
        kill (fn [which]
               (when-let [killer (which sys)]
                 (killer)))]
    (dorun (map kill doomed)))
  (system))
