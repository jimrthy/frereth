(ns client.propagate
  "Forward tap objects to the log viewer web socket"
  (:require [integrant.core :as ig]))

(defn do-it
  "Propagate an object sent to tap> to the log-viewer front-end"
  [o]
  ;; FIXME: This really needs to send the message to web sockets
  ;; attached to the front-end worker(s)
  (prn o))

(defmethod ig/init-key ::monitor
  [_ _]
  (add-tap do-it)
  do-it)

(defmethod ig/halt-key! ::monitor
  [_ func]
  (remove-tap func))
