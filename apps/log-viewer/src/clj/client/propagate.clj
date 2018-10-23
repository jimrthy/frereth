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
  (try
    (lib/post-message uuid o)
    (catch Exception ex
      (println "Posting message failed:" ex))))

(defmethod ig/init-key ::monitor
  [_ _]
  ;; FIXME: This should really start when a browser signals
  ;; that a log-viewer World is ready to begin.
  ;; Realistically, that's part of an internal Component System for
  ;; those World(s).
  (let [func (partial do-it lib/test-key)]
    (add-tap func)
    func))

(defmethod ig/halt-key! ::monitor
  [_ func]
  (remove-tap func))
