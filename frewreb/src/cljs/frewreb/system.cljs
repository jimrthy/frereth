(ns frewreb.system
  (:require [frewreb.render :as draw]
            [frewreb.communication :as comms]))

(defn init
  []
  {:communications (comms/init)
   :core (draw/init)})

(defn start
  [system]
  (comment (js/alert "start everything"))
  (into system {:communications (comms/start (:communications system))
                :core (draw/start (:core system))}))

(defn stop
  [system]
  (into system {:communications (comms/stop (:communications system))
                :core (draw/stop (:core system))}))
