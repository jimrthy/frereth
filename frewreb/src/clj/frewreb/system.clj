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
   :internal-messaging-killer nil})

(defn start
  "Peform the side-effects to make the system usable"
  [system]
  (let [c (async/chan)
        ->renderer-atom (atom nil)
        socket-router (route/build-socket-handler ->renderer-atom c)]
    (into system {:web-killer (httpd/start-web route/all-routes 8090)
                  :socket-killer (httpd/start-web socket-router 8091)
                  :internal-messaging-killer (fn []
                                               (async/close! c))}))
  system)

(defn stop
  "Perform the side-effects to shut a system down"
  [system]
  (let [doomed [:web-killer :socket-killer :internal-messaging-killer]
        kill (fn [which]
               (when-let [killer (which system)]
                 (killer)))]
    (dorun (map kill doomed)))
  (system))
