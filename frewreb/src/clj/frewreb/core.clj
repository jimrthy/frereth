(ns frewreb.core
  (:require [frewreb.system :as system]))

(defn -main [& args]
  (let [dead (system/system)
        alive (system/start dead)]))
