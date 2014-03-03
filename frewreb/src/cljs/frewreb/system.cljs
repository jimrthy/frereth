(ns frewreb.system
  (:require [frewreb.core :as core]
            [frewreb.communication :as comms]))

(defn init
  []
  {:communications (comms/init)
   :core (core/init)})

(defn start
  [system]
  (comment (js/alert "start everything"))
  (into system {:communications (comms/start (:communications system))
                :core (core/start (:core system))}))

(defn stop
  [system]
  (into system {:communications (comms/stop (:communications system))
                :core (core/stop (:core system))}))
