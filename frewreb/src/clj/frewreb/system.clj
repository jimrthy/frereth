(ns frewreb.system
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [frewreb.server :as httpd]
            [frewreb.router :as route]
            [frodo.web :as frodo]
            [nomad :refer [defconfig]]))

(defn system
  "Returns a new instance of the whole application"
  []
  {:web-server (ref nil)})

(defn start
  "Peform the side-effects to make the system usable"
  [system]
  (defconfig cfg (io/resource "config/nomad-config.edn"))
  (#'frodo/start-web-server! (:web-server system) (cfg))
  system)

(defn stop
  "Perform the side-effects to shut a system down"
  [system]
  (when-let [server (:web-server system)]
    (#'frodo/stop-web-server! server))  
  system)
