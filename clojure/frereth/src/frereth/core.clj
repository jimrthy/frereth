(ns frereth.core
  (:gen-class)
  (:require [com.stuartsierra.component :as component]
            [frereth.system :as system]))

(defn -main
  [& args]
  (let [dead-system (system/init {})
        sys (component/start dead-system)
        done (:finished sys)]
    (try @done
         (finally (component/stop sys)))))
