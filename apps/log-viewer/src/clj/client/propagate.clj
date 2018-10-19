(ns client.propagate
  "Forward tap objects to the log viewer web socket"
  (:require [integrant.core :as ig]
            [renderer.lib :as lib]))

(defn do-it
  "Propagate an object sent to tap> to the log-viewer front-end"
  [uuid o]
  ;; FIXME: This really needs to send the message to web sockets
  ;; attached to the front-end worker(s)
  (prn o)
  (lib/post-message uuid o))

(defmethod ig/init-key ::monitor
  [_ _]
  ;; This UUID is just one that I generated randomly at the REPL.
  ;; Really want/need to just generate something like 256 crypto-strong
  ;; random bits and use that as the key for the app.
  ;; TODO: Worry about that later.
  (let [func (partial do-it #uuid "de5837db-8041-4524-8ca9-f9b66b543401")]
    (add-tap func)
    func))

(defmethod ig/halt-key! ::monitor
  [_ func]
  (remove-tap func))
