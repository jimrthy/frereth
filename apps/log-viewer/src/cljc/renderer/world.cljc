(ns renderer.world
  (:require [clojure.spec.alpha :as s]
            [shared.specs :as specs]))

(s/def ::world-state #{::active
                       ::forked
                       ::forking
                       ::pending})
;; This is whatever makes sense for the world implementation.
;; This seems like it will probably always be a map?, but it could very
;; easily also be a mutable Object (though that seems like a terrible
;; idea).
(s/def ::internal-state any?)
(s/def ::world (s/keys :req [::time-in-state
                             ::connection-state
                             ::internal-state]))

(s/def :frereth/world-id :frereth/pid)
(s/def :frereth/worlds (s/map-of :frereth/world-id ::world))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef get-world
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-id)
  :ret (s/nilable ::world))
(defn get-world
  [world-map world-key]
  (get world-map world-key))

(s/fdef state-match?
  :args (s/cat :world ::world
               :state ::connection-state))
(defn state-match?
  [world state]
  (= (::connection-state world)
     state))

(s/fdef get-world-in-state
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-id
               :connection-state ::connection-state)
  :ret (s/nilable ::world))
(defn get-world-in-state
  [world-map world-key state]
  (when-let [world (get-world world-map world-key)]
    (when (state-match? world state)
      world)))

(s/fdef get-active-world
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-id)
  :ret (s/nilable ::world))
(defn get-active-world
  [world-map world-key]
  (get-world-in-state world-map world-key ::active))

(s/fdef get-pending-world
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-id)
  :ret (s/nilable ::world))
(defn get-pending-world
  [world-map world-key]
  (get-world-in-state world-map world-key ::pending))
