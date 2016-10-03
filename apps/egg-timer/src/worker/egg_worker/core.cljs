(ns egg-worker.core
  (:require [cljs.core.async :as async])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(enable-console-print!)

(defn main []
  (.addEventListener js/self
                     "message"
                     (fn [e]
                       (.log js/console "web worker received message" e)
                       (go (async/<! (async/timeout 1000))
                           ;; Two things need to change here
                           ;; 1. Use transit instead of pr-str/read
                           ;; 2. Transfer ownership instead of just transmitting the string
                           ;; (odds are lots of data will be involved here)
                           (.postMessage js/self (pr-str [:h2 "Work done!"])))))
  (println "Started egg worker*"))

(main)
