(ns frewreb.system
  (:require [frewreb.render :as draw]
            [frewreb.communication :as comms]
            [frewreb.connection :as connect]))

(defn init
  []
  {:communications (comms/init)
   :renderer (draw/init)
   :connection (connect/init)})

(defn start
  [system]
  (comment (js/alert "start everything"))
  (let [communications (comms/start (:communications system))
        renderer (draw/start (:renderer system))]
    (into system {:connection (connect/start  {:connection (:connection system)
                                               :communications communications
                                               :renderer renderer})})))

(defn stop
  [system]
  (into system {:connection (connect/stop system)
                :communications (comms/stop (:communications system))
                :renderer (draw/stop (:renderer system))}))
