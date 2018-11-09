(ns backend.system
  (:require [backend.web.server]
            [server.log-gen]))

(defn ctor [opts]
  ;; FIXME: Each component should use its own subset of opts

  ;; FIXME: Need to register World apps with the new client.registrar.

  ;; Changes in here don't really show up through a simple
  ;; call to reset.
  ;; FIXME: That isn't acceptable.
  ;; It has something to do with the way the boot task was defined.
  {:backend.web.server/web-server opts
   :server.log-gen/samples opts})
