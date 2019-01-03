(ns renderer.world
  (:require [clojure.spec.alpha :as s]
            [shared.specs :as specs]))

(s/def ::world-connection-state #{::active
                                  ::forked
                                  ::forking
                                  ::pending})
;; This is whatever makes sense for the world implementation.
;; This seems like it will probably always be a map?, but it could very
;; easily also be a mutable Object (though that seems like a terrible
;; idea).
(s/def ::world-internal-state any?)
(s/def ::world (s/keys :req [::time-in-state
                             ::world-connection-state
                             ::world-internal-state]))

(s/def :frereth/world-id :frereth/pid)
(s/def :frereth/worlds (s/map-of :frereth/world-id ::world))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public
