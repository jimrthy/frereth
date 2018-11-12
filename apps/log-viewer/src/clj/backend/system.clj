(ns backend.system
  (:require [backend.web.server]
            [client.propagate :as propagate]
            [server.log-gen]))

(defn ctor [opts]
  ;; FIXME: Each component should use its own subset of opts

  ;; Changes in here don't really show up through a simple
  ;; call to reset.
  ;; FIXME: That isn't acceptable.
  ;; It has something to do with the way the boot task was defined.
  {:backend.web.server/web-server opts
   ::propagate/monitor opts
   :server.log-gen/samples opts})
