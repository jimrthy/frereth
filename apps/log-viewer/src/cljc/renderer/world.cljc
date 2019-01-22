(ns renderer.world
  ;; FIXME: Move this ns under shared
  (:require [clojure.spec.alpha :as s]
            ;;
            [frereth.apps.login-viewer.specs]
            [shared.specs :as specs]))

(s/def ::connection-state #{::active     ; we've ACKed the browser's fork
                            ::forked     ; Q: diff between this and forking?
                            ::forking    ; received source code. Ready to fork
                            ::fsm-error  ; Tried an illegal state transition
                            ::pending    ; browser would like to fork
                            })
;; This is whatever makes sense for the world implementation.
;; This seems like it will probably always be a map?, but it could very
;; easily also be a mutable Object (though that seems like a terrible
;; idea).
(s/def ::internal-state any?)
(s/def ::world (s/keys :req [::specs/time-in-state
                             ::connection-state
                             ::internal-state]
                       :opt [:frereth/renderer->client]))

(s/def :frereth/world-key :frereth/pid)
(s/def :frereth/worlds (s/map-of :frereth/world-key ::world))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public

(s/fdef get-world
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key)
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
               :world-key :frereth/world-key
               :connection-state ::connection-state)
  :ret (s/nilable ::world))
(defn get-world-in-state
  [world-map world-key state]
  (when-let [world (get-world world-map world-key)]
    (when (state-match? world state)
      world)))

(s/fdef get-active
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key)
  :ret (s/nilable ::world))
(defn get-active
  [world-map world-key]
  (get-world-in-state world-map world-key ::active))

(s/fdef activate-pending
  :args (s/cat :world ::world
               :client :frereth/renderer->client)
  :fn (s/and #(= ::pending (-> % :args :world ::connection-state))
             #(or (= ::active (:ret %))
                  (= ::fsm-error (:ret %))))
  :ret ::world)
(defn activate-pending
  [world client]
  (-> world
      (update ::connection-state
              (fn [current]
                (if (= ::pending current)
                  ::active
                  (let []
                    (println "")
                    ::fsm-error))))
      (assoc ::specs/time-in-state
             ;; This is the 4th time I'm calling this.
             ;; It's tough to remember.
             ;; TODO: Isolate/generalize all of them
             ;; Bigger TODO: either make this usable from cljs or move
             ;; it to an unshared ns
             (java.util.Date.)
             :frereth/renderer->client client)))

(s/fdef add-pending
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key
               :initial-state ::internal-state)
  :ret :frereth/worlds)
(defn add-pending
  "Set up a new world that's waiting for the connection signal"
  [world-map world-key initial-state]
  ;; time-in-state is set in at least 3 different places now.
  ;; TODO: Refactor this into its own function so I don't have
  ;; to update multiple places if/when I decide to change its
  ;; implementation again.
  (assoc world-map world-key {::specs/time-in-state (java.util.Date.)
                              ::connection-state ::pending
                              ::internal-state initial-state}))

(s/fdef get-pending
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key)
  :ret (s/nilable ::world))
(defn get-pending
  [world-map world-key]
  (get-world-in-state world-map world-key ::pending))
