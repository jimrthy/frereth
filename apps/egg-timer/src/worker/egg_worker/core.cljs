(ns egg-worker.core)

(enable-console-print!)

(.addEventListener js/self
                   "message"
                   (fn [e]
                     (.log js/console "web worker received message" e)
                     (.postMessage js/self (pr-str [:h2 "Work done!"]))))

(println "Started egg worker*")
