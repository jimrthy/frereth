(ns backend.system
  (:require [backend.web.server]
            [client.propagate]
            [server.log-gen]))

(defn ctor [opts]
  ;; FIXME: Each component should have its own set of options

  ;; Changes in here don't really show up through a simple
  ;; call to reset.
  ;; FIXME: That isn't acceptable.
  {:backend.web.server/web-server opts
   :client.propagate/monitor opts
   :server.log-gen/samples opts})
