(ns shared.world
  (:require [clojure.spec.alpha :as s]
            ;;
            [frereth.apps.login-viewer.specs]
            [shared.specs :as specs]))

(s/def ::connection-state #{::active      ; we've ACKed the browser's fork
                            ::deactivated ; Web socket has been closed
                            ::forked      ; Q: diff between this and forking?
                            ::forking     ; received source code. Ready to fork
                            ::fsm-error   ; Tried an illegal state transition
                            ::pending     ; browser would like to fork
                            })
;; This is whatever makes sense for the world implementation.
;; This seems like it will probably always be a map?, but it could very
;; easily also be a mutable Object (though that seems like a terrible
;; idea).
(s/def ::internal-state any?)
(s/def ::world-without-history (s/keys :req [::specs/time-in-state
                                             ::connection-state
                                             ::internal-state]
                                       :opt [:frereth/renderer->client]))
(s/def ::history (s/coll-of ::world-without-history))
(s/def ::world (s/merge ::world-without-history
                        (s/keys :req [::history])))

(s/def :frereth/world-key :frereth/pid)
(s/def :frereth/worlds (s/map-of :frereth/world-key ::world))

(s/def ::fsm-transition
  (s/fspec :args (s/cat :world ::world)
           :ret ::world))
(s/def ::legal-transition?
  (s/nilable (s/fspec :args (s/cat :world ::world
                                   :next-state ::connection-state)
                      :ret boolean?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internal Implementation

;;; FIXME: Add a fsm.cljc and make the state graph declarative.
(s/fdef update-world-state
  :args (s/or :simple (s/cat :world-map :frereth/worlds
                             :world-key :frereth/world-key
                             :connection-state ::connection-state)
              :checked (s/cat :world-map :frereth/worlds
                              :world-key :frereth/world-key
                              :connection-state ::connection-state
                              :pre-check ::legal-transition?)
              :extras (s/cat :world-map :frereth/worlds
                             :world-key :frereth/world-key
                             :connection-state ::connection-state
                             :pre-check ::legal-transition?
                             :transition ::fsm-transition)))
(defn update-world-state
  ([world-map world-key connection-state]
   (update world-map world-key
           (fn [world]
             (let [history (::history world)
                   current (dissoc world ::history)]
               (-> world
                   (assoc {::specs/time-in-state (java.util.Date.)
                           ::connection-state connection-state})
                   (update ::history conj current))))))
  ([world-map world-key connection-state pre-check]
   (let [current (get world-map world-key)
         next-state
         (if (or (not pre-check)
                 (pre-check current connection-state))
           connection-state
           ::fsm-error)]
     (update-world-state world-map world-key next-state)))
  ([world-map world-key connection-state pre-check transition]
   (let [updated
         (update-world-state world-map
                             world-key
                             connection-state
                             pre-check)]
     (update world-map world-key transition))))

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
  [world-map world-key client]
  (update-world-state world-map world-key ::active
                      (fn [world]
                        (= (::connection-state world) ::pending))
                      (fn  [world]
                        (assoc world :frereth/renderer->client client))))

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
  (update-world-state world-map world-key ::pending
                      nil
                      #(assoc %
                              ::internal-state initial-state)))

(s/fdef deactivate
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key)
  :ret :frereth/worlds)
(defn deactivate
  [world-map world-key]
  (update-world-state world-map world-key ::deactivated))

(s/fdef get-pending
  :args (s/cat :world-map :frereth/worlds
               :world-key :frereth/world-key)
  :ret (s/nilable ::world))
(defn get-pending
  [world-map world-key]
  (get-world-in-state world-map world-key ::pending))
